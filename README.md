# 工作咩闹钟
<img width="240" height="240" src="https://github.com/user-attachments/assets/de4e9f0b-16ed-4e68-a8eb-b9f039b12f64" />


[workdayAlarmClockGo](https://github.com/zanjie1999/workdayAlarmClockGo)的Android服务端

可以在每次在设定的网抑云歌单中随机播放指定分钟时长（默认4分钟大约1~2首）的音乐作为闹钟铃声，在全部播过一遍之前不会重复，  
另外可以作为网抑云音乐播放器使用，随机播放永不重复，实现除语音助手外的智能音响应有的功能，部分vip也歌曲能放，吊打小爱音响！  
其实是重构了6年前(2017)的一个Python3写的小程序

本程序用于播放声音和启动程序，只是一个服务端，目标设备是带蓝牙的随身Wifi，全靠Golang写的服务在8080端口的Web服务交互，app本身就是一个控制台，右上角的按钮也可以控制播放，具体你可以自己点点看  
兼容Android4.0及以上，在Android13开发，用于骁龙210的Android5.1，无需root即可完美运行

如果要编译，请将Go编译输出的linux arm的二进制文件和linux arm64的二进制文件重命名放到
```
项目目录/app/libs/armeabi-v7a/libWorkdayAlarmClock.so
项目目录/app/libs/arm64-v8a/libWorkdayAlarmClock.so
项目目录/app/libs/x86/libWorkdayAlarmClock.so
```
你可以把文件换成你自己的程序，使用本程序作为启动器  
需要打包release包二进制文件才会被打包进去，然后安装启动  
```
adb install -r .\app\release\app-release.apk ; adb shell am start -n com.zyyme.workdayalarmclock/.MainActivity
```

## 使用用法
在右边Releases下载apk安装：  
`app-full` 通常用这个就行  
`app-nolauncher` 部分按键机不能设置默认桌面，用它就不会出现桌面选择  
`x86` 例如叮咚play，F503A平板等Intel CPU的设备  

### 部分系统比如MIUI或ColorOS需要长按图标到应用详情的电池设置中允许自启动，电池不优化，允许完全后台行为
然后打开 http://127.0.0.1:8080 进行配置，  
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

## <del>文件flag</del> 右上角菜单
23.0开始迁移到了app右上角的菜单里  

<img width="428" height="262" src="https://github.com/user-attachments/assets/f613edf2-40b0-416a-b6ec-55cbedf06261" />

每个功能是做什么的应该还挺直观的，下面是一些注意事项  
1. “开机开热点”只适合比较新的系统，例如android9,需要开启辅助功能权限，是做给小魔镜用的
2. “授权熄屏权限”会引导你打开设备管理员权限，在闹钟开始时会自动亮屏，在闹钟结束时会自动锁屏  
3. 如果你还安装了投屏软件或是小爱同学app，打开“自动回到时钟”，会在别的app停止播放声音后10秒回到时钟界面，解决语音唤醒后界面不会自动消失的问题


## IP摄像头服务
在右上角菜单中打开“IP摄像头”，第一次开启时需要授权摄像头权限。服务监听 `8880` 端口，摄像头编号从 `1` 开始  
可以在“摄像头密码”设置访问密码，留空则没有密码  
直接访问下面的地址可以打开摄像头播放页：  
```
http://设备IP:8880/
```
低延迟直播，秒杀市面上已有的软件，依然兼容到android4.0，突破兼容下限  
支持部分多摄像头的手机（有些是完全不暴露的是私有的这个没办法）

播放页中填写“摄像头密码”和编号，点击“播放”即可观看 H.264 视频。也可以直接访问 MJPEG 流：  
```
# 第一个摄像头，无密码
http://设备IP:8880/1

# 设置密码 abc 后
http://设备IP:8880/abc/1
```

Android 5.0 及以上还提供 H.264 fMP4 流，路径中加入 `avc` 即可，可以放到OBS中作为媒体输入  
```
http://设备IP:8880/avc/1
http://设备IP:8880/abc/avc/1
```

另外还可以强制指定分辨率档位，默认会自动根据性能选择，数字越小越清晰
```
# 第一个摄像头第一档
http://设备IP:8880/1/1
# 有密码 abc
http://设备IP:8880/abc/1/1
```

## 摄像头自动亮度
在右上角菜单打开“摄像头自动亮度”后，可以用前置摄像头估算环境亮度并调整屏幕为 0～4 档。IP摄像头正在使用摄像头时会优先使用 IP摄像头画面，自动亮度采样会暂停，非连续检测会在采样完成后释放摄像头  

开启IP摄像头服务后，获取当前亮度档位的接口为：
```
# 无密码
http://设备IP:8880/brightness

# 设置密码 abc 后
http://设备IP:8880/abc/brightness
```
接口返回纯文本数字 `0`～`4`，例如 `3`。


## 时钟模式 （智能手表）
点击控制台顶栏任意非右侧按钮的位置，或者点击常驻的通知，即可开启，或是作为双击电源键打开的相机来打开，勾选 时钟模式 可以默认启动到时钟模式  

<img width="430" height="206" src="https://github.com/user-attachments/assets/e5f55a3a-3f34-4804-bb00-512225d70d67" />

适合在小屏幕触摸屏设备上使用，比如说智能手表，大屏设备也可以用，有自适应  

<img width="250" src="https://github.com/user-attachments/assets/2433a859-558c-4963-b88e-dbd601c4e176" />

点击时间可以开关保持亮屏的功能  
点击日期可以切换亮色和暗色模式  
长按时间可以停止播放  
长按日期可以锁屏  
当全屏时，上下左右滑动单击将控制播放
支持作为双击电源键打开的相机，双击后启动时钟模式
长按音量键可以上下一首

收起后（竖屏）：  
<img width="250" alt="image" src="https://github.com/user-attachments/assets/7d99bc16-155e-4743-a34e-c06b3e62604e" />

手表实机（也支持这种底部被切掉一块的屏幕）：  
并且拥有环形的进度条  
<img width="902" height="1105" alt="image" src="https://github.com/user-attachments/assets/149a5c37-e329-4cc1-adc0-51aef222fa3d" />


## 大屏时钟模式
在右上角菜单中勾选 使用大屏时钟 即可开启，再点击顶部空白区域即可进入，勾选 时钟模式 可以默认启动到时钟模式  
<img width="1024" height="600" src="https://github.com/user-attachments/assets/a383513f-9558-4da5-8b6b-70c995f70056" />  
长按中间的空白区域，可以进入设置，调节各个组件的位置和设置壁纸  
将图片放到内置存储的zyymeWallpaper中可每小时随机轮换  
界面为横屏设计，但是不限制屏幕方向，你看可以自行旋转屏幕  

小提示，你可以把内容拼在 `http://192.168.1.152:8080/echo?msg=WEATHERAL%20` 后面，让内容显示在顶部歌词的位置，虽然这是为天气预警信息显示设计的，但你可以把任意信息推到上面显示

## 应用列表
<img width="430" height="268" src="https://github.com/user-attachments/assets/48efb0ac-5c74-49f8-9de2-e72fe386a3ee" />

代替启动器（也就是桌面），实现启动器启动应用这一功能，支持纯按键操作  
控制台长按顶栏，点击时钟模式顶部（横屏没有这块），“应用”按钮，按HOME键，都可以打开  
长按应用图标在列表中置顶/取消置顶，长按名称部分进入应用信息


## 通知转发
填写通知转发URL后，收到的通知会以“标题：内容”的格式拼到url的最后进行发送  
这个功能可以将运行了工作咩闹钟的这台手机的通知和短信推送到指定的推送服务器  
如果是常驻通知，只会在出现时通知 
举个例子，比如你输入
```
https://abc.com/api?message=
```
推送的时候就会请求
```
https://abc.com/api?message=通知标题：通知内容
```
还可以通过标签替换，比如  
```
https://abc.com/api?message=应用{app}包名是{pkg}发送了标题为{title}内容为{msg}的通知
```


## 特殊设备适配
### 一说宝宝1s
摸额头的触摸区域将瞬间显示当前电量强度，分为6格，越往右电量越高，18%左右的时候会显示一个（T T）的表情，要没电了会亮红灯，后续显示表情动画  
按下鼻子为停止播放，顶部播放控制按钮也可以使用

### 叮咚Play
请安装app-x86的apk  
关闭了一些硬件加速避免频繁更新布局导致Intel的GPU驱动崩溃  
勿扰模式的按键：  
短按播放/暂停  
双击下一首  
三击上一首  
3秒停止  
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

### 六个男孩智能科技的小魔镜
正面唯一的按钮：  
长按10秒启动小魔镜App  
短按播放/暂停  
双击下一首  
三击上一首  
3秒停止  
在设置中打开双击电源及打开相机，并双击电源选择 工作咩闹钟 始终打开，则可以双击电源键回到时钟模式  

### Bonjour闹钟
使用顶部触摸按钮按下去来控制（触摸容易误触，用不了一点）：  
短按播放/暂停  
双击下一首  
三击上一首  
3秒停止  
长按1秒音量+ 下一首
长按1秒音量- 上一首
开机启动需要将 工作咩闹钟 设置为启动器  

### 协议 咩License
使用此项目视为您已阅读并同意遵守 [此LICENSE](https://github.com/zanjie1999/LICENSE)   
Using this project is deemed to indicate that you have read and agreed to abide by [this LICENSE](https://github.com/zanjie1999/LICENSE)   
