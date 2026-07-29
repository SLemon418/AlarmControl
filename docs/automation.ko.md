# 자동화: Samsung Routines, Tasker, MacroDroid

[English](automation.md) | **한국어**

AlarmControl은 서로 분리된 두 가지 로컬 자동화 경로를 제공합니다.

1. **Samsung 모드 및 루틴(권장):** AlarmControl의 동적 App Shortcut을 선택합니다. 외부
   자동화를 켜거나 토큰을 입력할 필요가 없습니다.
2. **Tasker, MacroDroid 및 호환 도구:** 공개 수신기에 명시적·인증된 브로드캐스트를
   전송합니다.

두 경로 모두 같은 `ProfileController`에 위임하며 모든 처리는 기기 안에서 이루어집니다.
AlarmControl은 `INTERNET` 권한이 없고 네트워크를 호출하지 않습니다.

## Samsung 모드 및 루틴 설정

1. AlarmControl을 한 번 열어 동적 바로가기를 게시합니다.
2. **설정 → 모드 및 루틴 → 루틴**에서 새 루틴을 만듭니다.
3. **언제 실행할까요?**에서 조건을 선택합니다. 첫 검증에는 **수동으로 실행**이 안전합니다.
4. **무엇을 할까요?**에서 **애플리케이션 → 앱을 열거나 앱 동작 바로 실행**을 선택합니다.
5. **AlarmControl**을 펼쳐 다음 중 하나를 선택합니다.
   - **필터링 켜기**
   - **필터링 일시 중지**
   - 게시된 경우 이름 있는 프로필 바로가기
6. 루틴을 저장합니다. 업무 시간처럼 상태가 바뀌는 조건에는 반대 동작 루틴도 만듭니다.

이 경로는 Android `ShortcutManager`를 통해 AlarmControl의 비공개 바로가기 trampoline을
호출합니다. **Tasker 또는 MacroDroid 허용**이나 `AUTH_TOKEN`이 필요하지 않습니다.

2026-07-27 Galaxy Note20 5G(Android 13, One UI 5.1), Samsung 모드 및 루틴,
Routine+ 1.0.60에서 실제 검증했습니다. 수동 **필터링 일시 중지** 루틴은 마스터 스위치를
켜짐에서 꺼짐으로 바꿨고, **필터링 켜기** 루틴은 다시 켰습니다. 이 기기의 Routine+에는
일반적인 **Send broadcast** 동작이 없었으며 App Shortcut 경로에는 Routine+가 필수가 아닙니다.

## 인증된 Intent 계약

이 경로는 명시적 브로드캐스트를 보낼 수 있는 Tasker, MacroDroid 또는 호환 도구에서만
사용합니다.

| 항목 | 값 |
|---|---|
| 필터 활성화 | action `com.alarmcontrol.automation.action.ENABLE_PROFILE` |
| 필터 비활성화 | action `com.alarmcontrol.automation.action.DISABLE_PROFILE` |
| 필수 extra | `com.alarmcontrol.automation.extra.AUTH_TOKEN` (`String`) |
| 선택 대상 | `com.alarmcontrol.automation.extra.PROFILE_ID` (`String`) |
| 필수 대상 | 패키지 `com.alarmcontrol` 또는 component `com.alarmcontrol/com.alarmcontrol.automation.ProfileToggleReceiver` |

**설정 → 자동화 → Tasker 또는 MacroDroid 허용**을 켜야 설치별 `AUTH_TOKEN`이 표시됩니다.
값을 `String` extra로 정확히 복사합니다. 토큰을 다시 생성하면 이전 토큰을 사용하는 모든
Routine/Tasker 작업은 즉시 무효가 됩니다. 토큰은 백업이나 감사 기록에 포함되지 않습니다.
대상 지정은 필수입니다. 다른 앱이 공개 Action을 구독해 토큰을 볼 수 없도록 AlarmControl은
암시적 브로드캐스트를 거부합니다.

### `PROFILE_ID` 동작

- 생략하거나 공백이면 독립적인 **필터 마스터 스위치**를 제어합니다. 일시 중지해도 개별
  규칙의 활성 상태는 보존됩니다.
- 값이 있으면 id 또는 이름이 일치하는 프로필을 제어합니다. 이름 비교는 대소문자를
  구분하지 않습니다.
- 이름 있는 프로필 도입 이전 자동화와의 호환을 위해 프로필이 없으면 규칙 id 또는 규칙
  이름도 확인합니다.

알 수 없는 action, 잘못된 대상, 누락/오류 토큰, 존재하지 않는 id는 호출 앱을 충돌시키지
않고 거부됩니다. 외부 요청은 브로드캐스트 폭주 방지를 위해 최근 1분간 12회로 제한됩니다.

AlarmControl의 **설정 → 자동화 → Tasker 또는 MacroDroid 허용**을 켜고 설치별 토큰을
확인한 뒤 위 표의 action, 명시적 패키지/component, String extra를 발신 도구에 설정합니다.
토큰을 암시적 브로드캐스트, 로그, 스크린샷 또는 공유 자동화 파일에 넣지 마세요.

## adb로 빠르게 확인하기

```sh
# 개별 규칙 상태를 보존하면서 필터 마스터 스위치 끄기
adb shell am broadcast \
  -a com.alarmcontrol.automation.action.DISABLE_PROFILE \
  -n com.alarmcontrol/com.alarmcontrol.automation.ProfileToggleReceiver \
  --es com.alarmcontrol.automation.extra.AUTH_TOKEN "<settings-token>"

# "Work" 프로필 켜기
adb shell am broadcast \
  -a com.alarmcontrol.automation.action.ENABLE_PROFILE \
  -n com.alarmcontrol/com.alarmcontrol.automation.ProfileToggleReceiver \
  --es com.alarmcontrol.automation.extra.AUTH_TOKEN "<settings-token>" \
  --es com.alarmcontrol.automation.extra.PROFILE_ID "Work"
```

## 빠른 설정 타일

알림창의 빠른 설정 편집 화면에서 **AlarmControl filtering** 타일을 추가합니다. 타일은 개별
규칙 상태를 보존한 채 마스터 스위치만 전환합니다. 앱 자체 기능이므로
**Tasker 또는 MacroDroid 허용**이나 `AUTH_TOKEN`이 필요하지 않습니다.

## 런처 바로가기

AlarmControl 아이콘을 길게 누르면 **필터 켜기**, **필터 일시 중지**와 런처가 허용하는 수의
이름 있는 프로필 바로가기가 표시됩니다. 빠른 설정 타일 및 외부 Intent와 동일한
`ProfileController`를 사용하며 **Tasker 또는 MacroDroid 허용** 설정의 영향을 받지
않습니다.

## 보안 설명

서드파티 자동화 도구는 AlarmControl의 signature 권한을 가질 수 없으므로 수신기에 사용자
정의 권한을 설정하지 않습니다. 대신 다음 네 로컬 방어를 적용합니다.

1. AlarmControl 패키지 또는 component를 명시한 요청만 허용
2. 기본값이 꺼진 사용자 opt-in
3. 상수 시간으로 비교하는 암호학적 무작위 설치별 토큰
4. 프로세스 로컬 분당 12회 속도 제한

이전에 Package를 비워 만든 자동화는 업데이트 후 `com.alarmcontrol`을 추가해야 합니다.
Samsung App Shortcut 경로는 이 공개 수신기나 토큰을 사용하지 않습니다.

AlarmControl은 감사 결과를 최대 200건만 보관합니다. 기록 항목은 시간, 소스, 동작, 대상
**타입**, 결과, 변경 수이며 토큰, 프로필/규칙 이름, 알림 내용은 저장하지 않습니다. 최근
5건은 설정 화면에서 문제 해결 용도로 확인할 수 있습니다.
