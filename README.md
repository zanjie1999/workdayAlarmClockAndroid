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
在右边Releases下载apk安装，部分系统比如MIUI需要设置允许自启动，电池不优化，然后打开 http://127.0.0.1:8080 进行配置，  
如果使用别的设备打开，需要将127.0.0.1换成设备的ip地址(可以在刚启动的日志看到或者安卓的wifi设置中看到)  
另外也可以作为一个终端使用，输入exit可以退出    
支持媒体按键，上一首的按钮在不播放时按一下可以播放默认的歌单，再按一下可以切换成随机播放，在播放时可以回到上一首（只有一首的记录）  
在暂停的时候按下一首可以停止（让没有停止按钮的设备可以停止）

点击顶栏空白的地方可以进入 [时钟模式](#时钟模式)，  
点击时间可以切换保持亮屏，点击日期可以切换浅色和深色主题

更多说明请看 [workdayAlarmClockGo 如何使用](https://github.com/zanjie1999/workdayAlarmClockGo#%E5%A6%82%E4%BD%95%[E4](https://github.com/zanjie1999/workdayAlarmClockGo#%E5%A6%82%E4%BD%95%E4%BD%BF%E7%94%A8)%BD%BF%E7%94%A8)

## 指令
```shell
# 退出
exit
# 启动
run
```
更多指令可以看[workdayAlarmClockGo 指令]([https://github.com/zanjie1999/workdayAlarmClockGo](https://github.com/zanjie1999/workdayAlarmClockGo#%E6%8C%87%E4%BB%A4))

## 文件flag
使用文件进行来进行一些简单配置  
- disable开机不启动  
- clock强制时钟模式  
下面来举个例子，在控制台模式中进行操作：
```shell
# 创建flag
touch disable
# 删除flag
rm disable
```
设置好了将在下次启动生效


## 特殊设备适配
### 时钟模式 （智能手表）  
点击顶栏任意非右侧按钮的位置，或者点击常驻的通知，即可开启  
适合在小屏幕触摸屏设备上使用，比如说智能手表，大屏设备也可以用，有自适应  
点击时间可以开关保持亮屏的功能  
点击日期可以切换亮色和暗色模式  
长按时间可以停止播放  
长按日期可以锁屏  

### 一说宝宝1s
摸额头的触摸区域将瞬间显示当前电量强度，分为6格，越往右电量越高，18%左右的时候会显示一个（T T）的表情，要没电了会亮红灯，后续显示表情动画  
按下鼻子为停止播放，顶部播放控制按钮也可以使用

### 叮咚Play
按勿扰模式的按键可以暂停播放，长按可以停止  
启动默认全屏时钟

### 多亲qf9 （按键机）  
使用上下左右键调节，确认键暂停，菜单键停止  
按下拨号键可以彻底退出程序  
可以点击通知来打开时钟模式，点应用图标打开控制台模式，此时通过方向键选择按钮按中间按键操作（可以达到触摸的效果）

### 黑皮诺科技的智能绘本机器人H22
就是绿色全志A33陪伴音箱
启动默认全屏时钟  
通过按键控制播放，需要先改按键，具体可以看BV1G9hdzkEz2  
适配了屏幕overscan

### 协议 咩License
使用此项目视为您已阅读并同意遵守 [此LICENSE](https://github.com/zanjie1999/LICENSE)   
Using this project is deemed to indicate that you have read and agreed to abide by [this LICENSE](https://github.com/zanjie1999/LICENSE)   
