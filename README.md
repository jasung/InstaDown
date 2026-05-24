# InstaDown

Instagram 공개 게시물의 이미지와 영상 파일을 선택해서 저장하는 Android 앱입니다.

## 주요 기능

- Android 공유 메뉴에서 Instagram 링크를 바로 받을 수 있습니다.
- 앱 안의 붙여넣기 버튼으로 클립보드 링크를 바로 미리보기까지 진행합니다.
- 공개 페이지와 embed 응답에서 노출되는 이미지/영상 후보만 미리보기로 보여줍니다.
- 여러 장의 사진이 있는 게시물도 각 미디어를 선택해서 저장할 수 있습니다.
- 선택한 사진은 `Pictures/InstaDown`, 영상은 `Movies/InstaDown`에 저장됩니다.
- 앱 실행 시 public repo의 `instadownversion.json`을 확인해 새 빌드가 있으면 업데이트 알림을 표시합니다.

비공개 게시물, 로그인이 필요한 게시물, 만료되었거나 지역 제한이 있는 미디어는 우회하지 않습니다. Instagram이 공개 응답에 다운로드 가능한 미디어 URL을 노출하지 않으면 앱은 실패 메시지를 보여줍니다.

## 업데이트 정보

앱은 아래 public JSON의 `versionCode`가 설치된 앱보다 높을 때 업데이트 알림을 띄웁니다.

```text
https://raw.githubusercontent.com/jasung/InstaDown/main/instadownversion.json
```

최신 APK는 저장소의 `release/InstaDown-latest.apk`에 둡니다.

## 빌드

디버그 빌드:

```sh
/Users/jasung/Documents/Codex/android-shared-env/bin/gradle-project.sh /Users/jasung/Documents/Codex/InDown assembleDebug
```

릴리즈 APK 내보내기:

```sh
/Users/jasung/Documents/Codex/android-shared-env/bin/gradle-project.sh /Users/jasung/Documents/Codex/InDown exportReleaseApk
```

내보내기 작업은 로컬 `apks/`에는 최신 3개만 유지하고, public repo용 `release/InstaDown-latest.apk`도 함께 갱신합니다.
