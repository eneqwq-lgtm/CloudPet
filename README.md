# ☁️ 云宝 - CloudPet

基于 AI-Live-Overflow 架构 + clawd-on-desk 的 Cloudling 云宝 SVG 素材的 Android 悬浮窗桌宠。

## 构建 APK

### 方式一：GitHub Actions（推荐）
1. 在 GitHub 创建仓库，推送此项目
2. 进入 Actions 标签页，点击 `Build APK` workflow
3. 运行完成后下载 artifact

### 方式二：Android Studio
1. 用 Android Studio 打开此项目
2. 等待 Gradle 同步完成
3. Build → Build Bundle(s) / APK → Build APK

## 安装使用
1. 安装 APK 后打开 App
2. 授予悬浮窗权限
3. 点击「召唤云宝」
4. 云宝就出现在屏幕上了！

## 与 Termux 联动

Termux 端运行状态监控，云宝会实时反应：
```bash
# 云宝思考中
python3 ~/.hermes/pet_monitor.py thinking

# 云宝工作中
python3 ~/.hermes/pet_monitor.py working

# 云宝睡觉
python3 ~/.hermes/pet_monitor.py sleeping

# 云宝说话
python3 ~/.hermes/pet_monitor.py speak "你好呀~"

# 持续监控模式（自动检测）
python3 ~/.hermes/pet_monitor.py monitor
```
