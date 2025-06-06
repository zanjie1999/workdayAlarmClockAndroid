# 工作咩闹钟
[workdayAlarmClockGo](https://github.com/zanjie1999/workdayAlarmClockGo)的Android服务端

可以在每次在设定的网抑云歌单中随机播放指定分钟时长（默认4分钟大约1~2首）的音乐作为闹钟铃声，在全部播过一遍之前不会重复，  
另外可以作为网抑云音乐播放器使用，随机播放永不重复，实现除语音助手外的智能音响应有的功能，部分vip也歌曲能放，吊打小爱音响！  
其实是重构了6年前(2017)的一个Python3写的小程序

本程序用于播放声音和启动程序，只是一个服务端，目标设备是带蓝牙的随身Wifi，全靠Golang写的服务在8080端口的Web服务交互，app本身就是一个控制台，右上角的按钮也可以控制播放，具体你可以自己点点看  
兼容Android4.0及以上，在Android13开发，用于骁龙210的Android5.1，无需root即可完美运行

如果要编译，请将Go编译输出的linux arm的二进制文件和linux arm64的二进制文件重命名放到
```
项目目录/app/libs/armeabi/libWorkdayAlarmClock.so
项目目录/app/libs/arm64-v8a/libWorkdayAlarmClock.so
```
你可以把文件换成你自己的程序，使用本程序作为启动器  
需要打包release包二进制文件才会被打包进去，然后安装启动  
```
adb install -r .\app\release\app-release.apk ; adb shell am start -n com.zyyme.workdayalarmclock/.MainActivity
```

## 使用用法
在右边Releases下载apk安装，部分系统比如MIUI需要设置允许自启动，电池不优化，然后打开 http://127.0.0.1:8080 进行配置，如果使用别的设备打开，需要将127.0.0.1换成设备的ip地址
另外也可以作为一个终端使用，输入exit可以退出  
支持媒体按键，上一首的按钮在不播放时按一下可以播放默认的歌单，再按一下可以切换成随机播放，在播放时可以回到上一首（只有一首的记录）

## 指令
```shell
# 退出
exit
# 启动
start
```
更多指令可以看[workdayAlarmClockGo](https://github.com/zanjie1999/workdayAlarmClockGo)

## 特殊设备适配
### 一说宝宝1s
摸额头的触摸区域将瞬间显示当前电量强度，分为6格，越往右电量越高，18%左右的时候会显示一个（T T）的表情，要没电了会亮红灯，后续显示表情动画  
按下鼻子为停止播放，顶部播放控制按钮也可以使用

### 叮咚Play
按勿扰模式的按键可以暂停播放，长按可以停止


### 协议 咩License
使用此项目视为您已阅读并同意遵守 [此LICENSE](https://github.com/zanjie1999/LICENSE)   
Using this project is deemed to indicate that you have read and agreed to abide by [this LICENSE](https://github.com/zanjie1999/LICENSE)   
