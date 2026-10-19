package id.kelolauangku.app.application

import android.content.Context
import android.content.ContentValues
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import id.kelolauangku.app.data.Database
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Disk and KDF methods run on App.worker. Session state is process-only. */
class Security(private val context: Context, databaseName:String="keuangan.db", private val keyPrefix:String="local-", private val elapsed:()->Long={SystemClock.elapsedRealtime()}, private val boot:()->Int={Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,0)}) {
    private val database = Database(context,databaseName)
    private var settings = JSONObject()
    @Volatile var unlocked = false
        private set
    @Volatile var authenticatedAt = 0L
        private set
    private var backgroundAt: Long? = null
    val configured get() = settings.has("pin_hash")
    val screenshots get() = settings.optBoolean("allow_screenshots")
    val autoLock get() = settings.optInt("auto_lock_seconds", 60)
    val biometricEnabled get() = settings.optBoolean("biometric_enabled")
    val wipeAfterFailures get() = settings.optInt("wipe_after_failures") == 15
    private val blobs=listOf("pin_hash","pin_salt","recovery_hash","recovery_salt","biometric_token","biometric_iv")
    private val flags=listOf("biometric_enabled","allow_screenshots")
    private val keyStore get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun save() {
        val values = ContentValues().apply {
            put("id",1)
            for(key in blobs)if(settings.has(key))put(key,decode(settings.getString(key)))else putNull(key)
            for(key in flags)put(key,if(settings.optBoolean(key))1 else 0)
            put("auto_lock_seconds",autoLock)
            for(key in listOf("kdf_iterations","failed_attempts","boot_marker","wipe_after_failures"))put(key,settings.optLong(key))
            put("locked_until_elapsed",settings.optLong("locked_until"))
        }
        database.writableDatabase.insertWithOnConflict("app_security",null,values,android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun load() {
        database.readableDatabase.rawQuery("SELECT * FROM app_security WHERE id=1",null).use {c ->
            settings=JSONObject()
            if(c.moveToFirst())for(i in 0 until c.columnCount) {
                val key=c.getColumnName(i)
                if(c.isNull(i))continue
                when(key){in blobs->settings.put(key,encode(c.getBlob(i)));in flags->settings.put(key,c.getInt(i)==1);"locked_until_elapsed"->settings.put("locked_until",c.getLong(i));else->settings.put(key,c.getLong(i))}
            }
        }
    }
    fun background() { if(backgroundAt == null) backgroundAt = elapsed() }
    fun foreground() {
        backgroundAt?.let { if(elapsed()-it >= autoLock*1000L) unlocked=false }
        backgroundAt=null
    }
    fun lock() { unlocked=false }
    fun authorized():Boolean { backgroundAt?.let { if(elapsed()-it >= autoLock*1000L) unlocked=false };return unlocked }
    fun unlock() { unlocked=true; authenticatedAt=elapsed() }
    fun recent() = authorized() && elapsed()-authenticatedAt < 300_000
    private fun bytes(size:Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
    private fun encode(value:ByteArray)=Base64.encodeToString(value,Base64.NO_WRAP)
    private fun decode(value:String)=Base64.decode(value,Base64.NO_WRAP)
    private fun pepper(): SecretKey {
        val store=keyStore
        if(store.containsAlias(keyPrefix+"pin-pepper")) return store.getKey(keyPrefix+"pin-pepper",null) as SecretKey
        check(!configured) { "Kunci keamanan tidak tersedia. Reset aplikasi diperlukan." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyPrefix+"pin-pepper",KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKey()
    }
    private fun derive(secret:CharArray,salt:ByteArray,iterations:Int):ByteArray {
        val spec=PBEKeySpec(secret,salt,iterations,256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    private fun hash(secret:CharArray,salt:ByteArray,iterations:Int):ByteArray {
        val derived=derive(secret,salt,iterations)
        return try { Mac.getInstance("HmacSHA256").apply { init(pepper()) }.doFinal(derived) } finally { derived.fill(0) }
    }
    fun setPin(pin:CharArray) {
        require(pin.size==6 && pin.all{it in '0'..'9'} && pin.toSet().size>1 && !pin.asList().zipWithNext().all{it.second-it.first==1} && !pin.asList().zipWithNext().all{it.first-it.second==1}) { "Gunakan PIN 6 digit yang tidak sama atau berurutan." }
        val salt=bytes(16)
        var iterations=100_000
        while(true) {
            val start=SystemClock.elapsedRealtime()
            derive(pin,salt,iterations).fill(0)
            val elapsed=(SystemClock.elapsedRealtime()-start).coerceAtLeast(1)
            if(elapsed>=200) break
            iterations=Math.multiplyExact(iterations,2)
        }
        val result=hash(pin,salt,iterations)
        settings.put("pin_hash",encode(result)).put("pin_salt",encode(salt)).put("kdf_iterations",iterations)
        settings.put("failed_attempts",0).put("locked_until",0)
        save()
    }
    fun recoveryKey():String {
        val key=bytes(24).joinToString(""){"%02x".format(it)}.chunked(8).joinToString("-")
        val salt=bytes(16)
        val secret=key.replace("-","").toCharArray()
        try { settings.put("recovery_hash",encode(hash(secret,salt,100_000))).put("recovery_salt",encode(salt));save() }
        finally {secret.fill(' ')}
        return key
    }
    fun remainingLockSeconds():Long {
        val now=elapsed()
        val currentBoot=boot()
        if(settings.optLong("locked_until")>0 && settings.optInt("boot_marker",-1)!=currentBoot) {
            settings.put("locked_until",now+delay(settings.optInt("failed_attempts"))).put("boot_marker",currentBoot);save()
        }
        return ((settings.optLong("locked_until")-now+999)/1000).coerceAtLeast(0)
    }
    private fun delay(n:Int)=when(n){in 0..4->0L;5->30_000L;6->60_000L;7->300_000L;else->900_000L}
    fun verify(secret:CharArray,recovery:Boolean=false) {
        val remaining=remainingLockSeconds()
        check(remaining==0L){"Tunggu $remaining detik sebelum mencoba lagi."}
        val prefix=if(recovery)"recovery" else "pin"
        val normalized=if(recovery) secret.concatToString().replace("-","").lowercase().toCharArray() else secret.copyOf()
        val valid=try {
            settings.has("${prefix}_hash") && MessageDigest.isEqual(decode(settings.getString("${prefix}_hash")),hash(normalized,decode(settings.getString("${prefix}_salt")),if(recovery)100_000 else settings.getInt("kdf_iterations")))
        } finally {normalized.fill(' ')}
        if(!valid) {
            val failures=settings.optInt("failed_attempts")+1
            settings.put("failed_attempts",failures).put("locked_until",elapsed()+delay(failures))
                .put("boot_marker",boot());save()
            if(wipeAfterFailures && failures>=15) {
                resetKeys()
                (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).clearApplicationUserData()
                error("Data aplikasi sedang direset.")
            }
            error(if(failures<5)"Kode salah. ${5-failures} percobaan sebelum jeda." else "Kode salah. Tunggu ${delay(failures)/1000} detik.")
        }
        settings.put("failed_attempts",0).put("locked_until",0);save()
    }
    fun preferences(seconds:Int,allowScreenshots:Boolean,wipe:Boolean=false) {
        require(seconds in listOf(0,30,60,300));settings.put("auto_lock_seconds",seconds).put("allow_screenshots",allowScreenshots).put("wipe_after_failures",if(wipe)15 else 0);save()
    }
    fun disableBiometric() {settings.put("biometric_enabled",false).remove("biometric_token");settings.remove("biometric_iv");save();keyStore.deleteEntry(keyPrefix+"biometric")}
    fun biometricCipher(create:Boolean=false):javax.crypto.Cipher {
        if(create) {
            keyStore.deleteEntry(keyPrefix+"biometric")
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(keyPrefix+"biometric",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true).setUserAuthenticationParameters(0,KeyProperties.AUTH_BIOMETRIC_STRONG)
                    .setInvalidatedByBiometricEnrollment(true).build())
            }.generateKey()
        }
        try {
            val key=keyStore.getKey(keyPrefix+"biometric",null) as? SecretKey ?: error("Aktifkan ulang biometrik dengan PIN.")
            return javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
                if(create) init(javax.crypto.Cipher.ENCRYPT_MODE,key)
                else init(javax.crypto.Cipher.DECRYPT_MODE,key,javax.crypto.spec.GCMParameterSpec(128,decode(settings.getString("biometric_iv"))))
            }
        }catch(e:Exception){settings.put("biometric_enabled",false);save();throw IllegalStateException("Biometrik berubah atau tidak tersedia. Gunakan PIN.")}
    }
    fun finishBiometric(cipher:javax.crypto.Cipher,create:Boolean) {
        if(create){settings.put("biometric_token",encode(cipher.doFinal("kelola-uangku-unlock".toByteArray()))).put("biometric_iv",encode(cipher.iv)).put("biometric_enabled",true);save()}
        else{check(MessageDigest.isEqual(cipher.doFinal(decode(settings.getString("biometric_token"))),"kelola-uangku-unlock".toByteArray())){"Biometrik gagal. Gunakan PIN."};settings.put("failed_attempts",0).put("locked_until",0);save()}
    }
    fun resetKeys() { keyStore.deleteEntry(keyPrefix+"pin-pepper"); keyStore.deleteEntry(keyPrefix+"biometric");if(keyPrefix=="local-")keyStore.deleteEntry("kelola-llm-key");lock() }
    fun close() { database.close() }
}
