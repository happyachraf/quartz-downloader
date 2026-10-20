package com.quartz.downloader

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns

/** Exposes only the verified, private update APK via an Android temporary URI grant. */
class UpdateApkProvider:ContentProvider(){
    override fun onCreate()=true
    private fun file(uri:Uri):java.io.File{
        require(uri.authority==context!!.packageName+".updates"&&uri.path=="/apk")
        return AppUpdates.readyFile(context!!)?:throw java.io.FileNotFoundException("No verified update")
    }
    override fun getType(uri:Uri):String{file(uri);return "application/vnd.android.package-archive"}
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor{
        val apk=file(uri);val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).apply{addRow(columns.map{when(it){OpenableColumns.DISPLAY_NAME->apk.name;OpenableColumns.SIZE->apk.length();else->null}})}
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor{require(mode=="r");return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.MODE_READ_ONLY)}
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=throw UnsupportedOperationException()
}
