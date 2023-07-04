package com.zyyme.workdayalarmclock

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.*
import android.Manifest
import android.os.Build
import androidx.annotation.RequiresApi


class MainActivity : AppCompatActivity() {

    val logViewB = StringBuilder()

    fun print2LogView(s:String?) {
        if (s != null) {
            runOnUiThread(Runnable {
                Log.d("logView", s)
                logViewB.append(s)
                logViewB.append("\n")
                findViewById<TextView>(R.id.logView).text = logViewB.toString()
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 存储空间权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE ) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
        }

        // 检查二进制文件更新
        try {
            val updateFile = File(Environment.getExternalStorageDirectory().absolutePath + "/libWorkdayAlarmClock.so")
            if (updateFile.exists()) {
                print2LogView("找到 /sdcard/libWorkdayAlarmClock.so 开始更新")
                val libdir = File(applicationInfo.nativeLibraryDir)
                if (!libdir.exists()) {
                    libdir.mkdir()
                }
                val inputStream: InputStream = FileInputStream(updateFile)
                val outputFile = File(libdir, "libWorkdayAlarmClock.so")
                val outputStream: OutputStream = FileOutputStream(outputFile)
                val buffer = ByteArray(1024)
                var length: Int
                while (inputStream.read(buffer).also { length = it } > 0) {
                    outputStream.write(buffer, 0, length)
                }
                outputStream.close()
                inputStream.close()
                print2LogView("更新完成")
            } else {
                print2LogView("没有找到 /sdcard/libWorkdayAlarmClock.so 不更新")
            }
        } catch (e: IOException) {
            e.printStackTrace()
            print2LogView(e.toString())
        }

        // 开一个新线程跑shell
        Thread(Runnable {
            try {
                val command = "ls -l " + applicationInfo.nativeLibraryDir + " /data/data/com.zyyme.workdayalarmclock /data/user/0/com.zyyme.workdayalarmclock; "+
                    "cd " + getFilesDir().getAbsolutePath() + ";pwd;whoami;" + applicationInfo.nativeLibraryDir + "/libWorkdayAlarmClock.so"
                val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(true)
                    .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    print2LogView(line)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                print2LogView(e.toString())
            }
        }).start()
    }
}