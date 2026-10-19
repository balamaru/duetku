package id.kelolauangku.app.application

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Called on the application worker, behind the local authentication gate. */
class LlmConfig(context:Context,private val authorized:()->Boolean,preferencesName:String="llm_config",private val alias:String="kelola-llm-key") {
    data class Config(val provider:String,val model:String,val key:String)
    private val prefs=context.getSharedPreferences(preferencesName,Context.MODE_PRIVATE)
    private fun store()=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    fun metadata():Pair<String,String> {check(authorized());return prefs.getString("provider","gemini")!! to prefs.getString("model","")!!}
    fun configured():Boolean {check(authorized());return prefs.contains("ciphertext")}
    fun load():Config? {
        check(authorized());if(!configured())return null
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,store().getKey(alias,null) as SecretKey,GCMParameterSpec(128,Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP)))
        val clear=cipher.doFinal(Base64.decode(prefs.getString("ciphertext",""),Base64.NO_WRAP))
        return try{val meta=metadata();Config(meta.first,meta.second,clear.toString(Charsets.UTF_8))}finally{clear.fill(0)}
    }
    fun save(provider:String,model:String,key:String) {
        check(authorized())
        require(provider in listOf("gemini","openai","claude")){"Provider tidak didukung."}
        require(model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,119}"))){"Isi LLM_MODEL dengan ID model dari penyedia."}
        val secret=if(key.isBlank()){val old=load();require(old!=null&&old.provider==provider){"Isi API key untuk provider ini."};old.key}else key.trim()
        require(secret.length in 10..1024 && secret.all{it.code in 33..126}){"Format API key tidak valid."}
        val ks=store()
        val encryptionKey=(ks.getKey(alias,null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,encryptionKey)
        val bytes=secret.toByteArray()
        val encrypted=try{cipher.doFinal(bytes)}finally{bytes.fill(0)}
        check(prefs.edit().putString("provider",provider).putString("model",model)
            .putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .putString("ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit()){"Konfigurasi belum tersimpan."}
    }
    fun clear(){check(authorized());check(prefs.edit().clear().commit());store().deleteEntry(alias)}
}
