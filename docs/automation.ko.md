# 자동화: Samsung Galaxy Routines에서 AlarmControl 제어하기

[English](automation.md) | **한국어**

AlarmControl은 하나의 공개 `BroadcastReceiver`
(`com.alarmcontrol.automation.ProfileToggleReceiver`)를 제공해 필터 마스터 스위치나 이름 있는
프로필을 켜고 끕니다. 아래 action과 extra 이름은 안정적으로 유지되는 공개 계약입니다.

모든 처리는 기기 안에서 이루어집니다. 수신기는 `INTERNET` 권한이 없으며 네트워크 호출을
하지 않습니다.

## Intent 계약

| 항목 | 값 |
|---|---|
| 필터 활성화 | action `com.alarmcontrol.automation.action.ENABLE_PROFILE` |
| 필터 비활성화 | action `com.alarmcontrol.automation.action.DISABLE_PROFILE` |
| 필수 extra | `com.alarmcontrol.automation.extra.AUTH_TOKEN` (`String`) |
| 선택 대상 | `com.alarmcontrol.automation.extra.PROFILE_ID` (`String`) |
| 대상 component | `com.alarmcontrol/com.alarmcontrol.automation.ProfileToggleReceiver` |

설정에서 **외부 자동화 허용**을 켜야 설치별 `AUTH_TOKEN`이 표시됩니다. 값을 `String` extra로
정확히 복사합니다. 토큰을 다시 생성하면 이전 토큰을 사용하는 모든 Routine/Tasker 작업은
즉시 무효가 됩니다. 토큰은 백업이나 감사 기록에 포함되지 않습니다.

### `PROFILE_ID` 동작

- 생략하거나 공백이면 독립적인 **필터 마스터 스위치**를 제어합니다. 일시 중지해도 개별
  규칙의 활성 상태는 보존됩니다.
- 값이 있으면 id 또는 이름이 일치하는 프로필을 제어합니다. 이름 비교는 대소문자를
  구분하지 않습니다.
- 이름 있는 프로필 도입 이전 자동화와의 호환을 위해 프로필이 없으면 규칙 id 또는 규칙
  이름도 확인합니다.

알 수 없는 action, 잘못된 대상, 누락/오류 토큰, 존재하지 않는 id는 호출 앱을 충돌시키지
않고 거부됩니다. 외부 요청은 브로드캐스트 폭주 방지를 위해 최근 1분간 12회로 제한됩니다.

## Samsung Modes and Routines 설정

Samsung Modes and Routines에는 임의 커스텀 Intent를 보내는 기본 액션이 없으므로 Good Lock의
**RoutinePlus(Routines+)** 모듈을 사용합니다.

1. Galaxy Store에서 **Good Lock**을 설치하고 **RoutinePlus**를 엽니다.
2. AlarmControl의 **설정 → 외부 자동화 허용**을 켜고 표시된 토큰을 복사합니다.
3. Routine을 만들거나 편집하고 **Then**에서 RoutinePlus 커스텀 액션의
   **Send broadcast**를 추가합니다.
4. 다음 값을 설정합니다.
   - **Action**: `com.alarmcontrol.automation.action.DISABLE_PROFILE` 또는
     `com.alarmcontrol.automation.action.ENABLE_PROFILE`
   - **Package**: `com.alarmcontrol`을 권장합니다.
   - **필수 Extra**: key `com.alarmcontrol.automation.extra.AUTH_TOKEN`, 형식 `String`,
     값은 AlarmControl에서 복사한 토큰입니다.
   - **선택 Extra**: key `com.alarmcontrol.automation.extra.PROFILE_ID`, 형식 `String`,
     값은 프로필 이름 또는 id입니다. 마스터 스위치는 이 extra를 생략합니다.
5. 필요한 **If** 조건을 추가하고 종료 조건에서는 반대 action을 실행하는 Routine을 만듭니다.

예를 들어 업무 장소 진입 시 `DISABLE_PROFILE`로 필터를 일시 중지하고, 나갈 때
`ENABLE_PROFILE`로 다시 켤 수 있습니다. 개별 규칙 활성 상태는 바뀌지 않습니다.

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
규칙 상태를 보존한 채 마스터 스위치만 전환합니다. 앱 자체 기능이므로 외부 자동화 허용
설정이나 `AUTH_TOKEN`이 필요하지 않습니다.

## 런처 바로가기

AlarmControl 아이콘을 길게 누르면 **필터 켜기**, **필터 일시 중지**와 런처가 허용하는 수의
이름 있는 프로필 바로가기가 표시됩니다. 빠른 설정 타일 및 외부 Intent와 동일한
`ProfileController`를 사용하며 외부 자동화 허용 설정의 영향을 받지 않습니다.

## 보안 설명

RoutinePlus와 Tasker는 별도 앱으로 실행되어 AlarmControl의 signature 권한을 가질 수 없으므로
수신기에 사용자 정의 권한을 설정하지 않습니다. 대신 다음 세 로컬 방어를 적용합니다.

1. 기본값이 꺼진 사용자 opt-in
2. 상수 시간으로 비교하는 암호학적 무작위 설치별 토큰
3. 프로세스 로컬 분당 12회 속도 제한

AlarmControl은 감사 결과를 최대 200건만 보관합니다. 기록 항목은 시간, 소스, 동작, 대상
**타입**, 결과, 변경 수이며 토큰, 프로필/규칙 이름, 알림 내용은 저장하지 않습니다. 최근
5건은 설정 화면에서 문제 해결 용도로 확인할 수 있습니다.
