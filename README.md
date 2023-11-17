# 工作咩闹钟
[workdayAlarmClockGo](https://github.com/zanjie1999/workdayAlarmClockGo)的Android服务端

可以在每次在设定的网抑云歌单中随机抽取2首作为闹钟铃声，  
另外可以作为网抑云音乐播放器使用，随机播放永不重复，实现除语音助手外的智能音响应有的功能  
其实是重构了6年前(2017)的一个Python3写的小程序

本程序用于播放声音和启动程序，只是一个服务端，目标设备是带蓝牙的随身Wifi，全靠Golang写的服务在8080端口的Web服务交互  
兼容Android4.0及以上，在Android13开发，用于骁龙210的Android5.1

如果要编译，请将Go编译输出的linux arm的二进制文件和linux arm64的二进制文件重命名放到
```
项目目录/app/libs/armeabi/libWorkdayAlarmClock.so
项目目录/app/libs/arm64-v8a/libWorkdayAlarmClock.so
```
你可以把文件换成你自己的程序，使用本程序作为启动器

## 使用用法
在右边Releases下载apk安装，部分系统比如MIUI需要设置允许自启动，电池不优化，然后打开 http://127.0.0.1:8080 进行配置，如果使用别的设备打开，需要将127.0.0.1换成设备的ip地址
另外也可以作为一个终端使用，输入exit可以退出
