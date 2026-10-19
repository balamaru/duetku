package id.kelolauangku.app.application

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Read-only, unexported provider; access requires an explicit per-file URI grant. */
class ReportProvider:ContentProvider() {
    override fun onCreate()=true
    private fun file(uri:Uri):File {
        val root=File(requireNotNull(context).cacheDir,"reports").canonicalFile
        val name=requireNotNull(uri.lastPathSegment)
        require(name.matches(Regex("laporan-[0-9a-f-]{36}\\.pdf")))
        return File(root,name).canonicalFile.also{require(it.parentFile==root&&it.isFile)}
    }
    override fun getType(uri:Uri)="application/pdf"
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {require(mode=="r");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)}
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val f=file(uri);val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply{addRow(columns.map{when(it){OpenableColumns.DISPLAY_NAME->"laporan-keuangan.pdf";OpenableColumns.SIZE->f.length();else->null}})}
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=throw UnsupportedOperationException()
}
