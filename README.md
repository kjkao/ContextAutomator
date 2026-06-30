# Context Automator

Context Automator 是一個 Android 自動化工具，讓你根據情境自動套用手機設定。

目前支援以 Wi-Fi、時間、藍牙、充電狀態、地理圍欄、前景 App 作為觸發條件，並自動執行鈴聲模式、音量、亮度與螢幕逾時等動作。

## 專案資訊

- App 名稱: Context Automator
- Package / namespace: `com.kjkao.contextautomator`
- 語言: Kotlin
- 架構: AndroidX, Room, ViewBinding
- Compile SDK: 34
- Target SDK: 34
- Min SDK: 26
- Kotlin: 1.9.24
- Android Gradle Plugin: 8.5.2

## 核心功能

### 觸發條件

1. Wi-Fi SSID
- 條件: Detected / Connected
- 輸入: Wi-Fi 名稱
- 範例: `HomeWiFi`

2. Time Point
- 條件: 每天固定時間觸發
- 輸入格式: `HH:mm`
- 範例: `08:30`

3. Bluetooth Device
- 條件: Detected / Connected
- 輸入: 藍牙裝置名稱
- 範例: `WH-1000XM5`

4. Charging State
- 條件: Charging / Not Charging
- 輸入: 不需要

5. Geofence
- 條件: Inside / Outside
- 輸入格式: `lat,lng,radiusMeters`
- 範例: `25.033964,121.564468,200`

6. App In Foreground
- 條件: Foreground / Not In Foreground
- 輸入: App package name
- 範例: `com.spotify.music`

### 執行動作

1. Ringer Mode
- Normal / Vibrate / Silent
- 需要 Do Not Disturb 權限

2. Ring Volume
- 0-100%

3. Media Volume
- 0-100%

4. Screen Brightness
- 0-255
- 需要修改系統設定權限

5. Screen Timeout
- 15-600 秒（UI 顯示）
- 需要修改系統設定權限

## 執行機制

- 主畫面啟動 Foreground Service 後，服務會持續掃描與比對規則
- Wi-Fi / Bluetooth 規則每 180 秒掃描一次
- Geofence / 前景 App 規則每 180 秒掃描一次
- 沒有有效規則時每 300 秒檢查一次
- Bluetooth discovery 最短間隔 10 分鐘
- 相同規則與相同動作在 30 分鐘內會做去重，避免重複套用
- 最近 24 小時的規則執行紀錄可在主畫面查看

## 時間規則

- 時間規則使用 AlarmManager 排程
- 每條時間規則每天只會觸發一次
- 觸發後會自動排到下一次

## 開機行為

- 開機後 automation 狀態會先被關閉
- 所有時間規則會取消
- 需要使用者回到 App 手動按 Start 再啟用

## 權限需求

### Runtime permissions

- ACCESS_FINE_LOCATION
- NEARBY_WIFI_DEVICES（Android 13+）
- POST_NOTIFICATIONS（Android 13+）
- BLUETOOTH_CONNECT / BLUETOOTH_SCAN（Android 12+）

### Special access / system settings

- Do Not Disturb access: 給 Ringer Mode 用
- Usage Access: 給前景 App 觸發條件用
- Modify system settings: 給亮度與螢幕逾時用

### 其他權限

- ACCESS_WIFI_STATE
- CHANGE_WIFI_STATE
- MODIFY_AUDIO_SETTINGS
- PACKAGE_USAGE_STATS
- WRITE_SETTINGS
- FOREGROUND_SERVICE
- FOREGROUND_SERVICE_DATA_SYNC
- SCHEDULE_EXACT_ALARM
- RECEIVE_BOOT_COMPLETED

## 建置需求

- JDK 17
- Android SDK Platform 34
- Android Studio 或 Gradle CLI

## 建置方式

### Debug

```bash
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

### Release

```bash
./gradlew assembleRelease --no-daemon
```

Windows:

```powershell
.\gradlew.bat assembleRelease --no-daemon
```

## 專案結構

```text
app/src/main/java/com/kjkao/contextautomator
├─ MainActivity.kt
├─ RuleEditorActivity.kt
├─ ContextAutomatorApp.kt
├─ alarm/
├─ audio/
├─ automation/
├─ data/
├─ domain/
├─ service/
└─ ui/
```

## 資料儲存

- Database: `context_automator.db`
- 規則資料表: `wifi_rules`
- 執行紀錄表: `rule_execution_history`

## 常見問題

1. 規則有命中但鈴聲沒變
- 檢查是否已開啟 Do Not Disturb access

2. Wi-Fi / 藍牙 pick 清單是空的
- 檢查位置、附近裝置與藍牙權限

3. 前景 App 規則不準
- 檢查 Usage Access 是否已授權

4. 亮度或螢幕逾時沒有變
- 檢查是否允許修改系統設定

## Git 換行設定

- 一般文字檔使用 LF
- `.bat` / `.cmd` 使用 CRLF
- 規則由 `.gitattributes` 控制
