# SYNC — Android

YouTube 음악 플레이어 앱의 Android 버전입니다.

## 빌드 방법

### GitHub Actions (권장)
1. 이 저장소를 GitHub에 Push
2. Actions 탭 → `Build SYNC APK` 워크플로 → `Run workflow`
3. 완료 후 `Artifacts`에서 APK 다운로드

### 로컬 빌드
```bash
# JDK 17 필요, Android SDK 필요
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

### Gradle Wrapper 설정 (최초 1회)
```bash
gradle wrapper --gradle-version 8.6
```
또는 Android Studio에서 프로젝트를 열면 자동으로 다운로드됩니다.

## 서명된 Release APK

GitHub Secrets에 아래 항목을 추가하세요:
- `KEYSTORE_BASE64`: `base64 -i keystore.jks` 출력값
- `KEYSTORE_PASSWORD`: 키스토어 비밀번호
- `KEY_ALIAS`: 키 별칭
- `KEY_PASSWORD`: 키 비밀번호

## 기능
- YouTube 음악 검색 및 재생 (IFrame Player API)
- 가사 동기화 (lrclib.net + NetEase 폴백)
- 즐겨찾기 / 플레이리스트
- 대기열, 셔플, 반복
- Echo 효과
- 가로 모드 → Now Playing 전체화면 자동 전환
- 다이나믹 배경 / 비주얼라이저
