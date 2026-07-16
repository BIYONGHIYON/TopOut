# Android 결과 타임라인 확대 UI 수정

## 변경 사항

- 시작/끝 구간 타임라인을 위쪽의 주 컨트롤로 배치했습니다.
- `선택된 구간 확대` 배율 슬라이더는 타임라인 아래로 이동했습니다.
- 배율 슬라이더는 전체 폭의 72%로 줄이고 높이와 텍스트를 축소했습니다.
- 시작/끝 핸들은 반지름 12dp, 터치 범위 34dp로 확대했습니다.
- 주 타임라인 트랙을 6dp로 키우고 전체 컨트롤 높이를 늘렸습니다.
- 두 슬라이더 사이에 7dp 간격을 두어 계층이 명확하게 보이도록 했습니다.

## 배율 유지

확대한 뒤 시작점 또는 종료점을 이동해도 현재 viewport span을 유지합니다. 따라서 배율 숫자가 갑자기 1배 쪽으로 내려가지 않습니다. 조정이 끝나면 같은 배율에서 선택 구간 중심만 다시 맞춥니다.

새 선택 구간이 현재 확대 화면보다 넓은 예외 상황에서만 두 핸들이 보이도록 최소한으로 축소합니다.

## Android 빌드 오류 수정

`PrecisionTrimSlider.kt`의 다음 명시적 import를 제거했습니다.

```kotlin
import androidx.compose.foundation.layout.weight
```

현재 Compose 버전에서는 `weight`가 `RowScope`/`ColumnScope`의 스코프 확장으로 제공됩니다. 명시적으로 import하면 내부 `RowColumnParentData.weight` 속성으로 잘못 해석되어 다음 오류가 발생할 수 있습니다.

```text
Cannot access 'var RowColumnParentData?.weight: Float': it is internal in file
```

`Row`와 `Column` 내부의 `Modifier.weight(...)` 호출은 그대로 유지됩니다.
