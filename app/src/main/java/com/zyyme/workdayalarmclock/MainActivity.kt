package com.zyyme.workdayalarmclock

import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader


class MainActivity : AppCompatActivity() {

    val logViewB = StringBuilder()

    fun print2LogView(s:String?) {
        if (s != null) {
            Log.d("logView", s)
            logViewB.append(s)
            logViewB.append("\n")
            findViewById<TextView>(R.id.logView).text = logViewB.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 复制一下到data分区下
//        try {
//            val inputStream: InputStream = assets.open("workdayAlarmClock-linux-arm")
//            val outputFile = File(filesDir, "workdayAlarmClock")
//            val outputStream: OutputStream = FileOutputStream(outputFile)
//            val buffer = ByteArray(1024)
//            var length: Int
//            while (inputStream.read(buffer).also { length = it } > 0) {
//                outputStream.write(buffer, 0, length)
//            }
//            outputStream.close()
//            inputStream.close()
//        } catch (e: IOException) {
//            e.printStackTrace()
//            print2LogView(e.toString())
//        }

        try {
            val command = "ls -l "+applicationInfo.nativeLibraryDir+" /data/data/com.zyyme.workdayalarmclock /data/user/0/com.zyyme.workdayalarmclock; "+
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
    }
}