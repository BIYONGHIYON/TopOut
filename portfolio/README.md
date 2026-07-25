# TopOut Responsive Portfolio

TopOut 1인 개발 프로젝트를 소개하는 반응형 정적 웹사이트입니다.
별도 빌드 도구 없이 `index.html`을 바로 열거나 Vercel, Netlify, GitHub Pages 등에 배포할 수 있습니다.

## 파일 구조

```text
topout_portfolio/
├── index.html
├── styles.css
├── script.js
├── README.md
└── assets/
    ├── topout-logo.png          # 앱 로고
    ├── topout-promo.mp4         # 1080×1920 세로 프로모션 영상
    └── topout-promo-poster.jpg  # 선택: 영상 썸네일
```

## 로고 추가

앱 로고를 아래 이름으로 저장하세요.

```text
assets/topout-logo.png
```

- 투명 배경 PNG 권장
- 정사각형 이미지 권장
- 파일이 없으면 `TO` 플레이스홀더가 표시됩니다.

## 프로모션 영상 추가

1분짜리 세로 영상을 아래 이름으로 저장하세요.

```text
assets/topout-promo.mp4
```

권장 사양:

- 해상도: 1080 × 1920
- 비율: 9:16
- 코덱: H.264
- 형식: MP4
- 브라우저 자동 재생은 사용하지 않고 컨트롤을 제공합니다.

썸네일이 있다면 다음 이름으로 추가할 수 있습니다.

```text
assets/topout-promo-poster.jpg
```

## 수정이 필요한 링크

`index.html` 하단의 다음 부분을 실제 정보로 교체하세요.

- `your-email@example.com`
- GitHub 링크의 `href="#"`
- 앱스토어/플레이스토어 링크가 준비되면 closing 영역에 추가

## 로컬 확인

파일을 직접 열어도 동작하지만, 영상 재생 테스트에는 로컬 서버를 권장합니다.

```bash
python3 -m http.server 8000
```

브라우저에서 `http://localhost:8000`을 엽니다.

## 배포

### Vercel

1. GitHub 저장소에 폴더 내용을 업로드합니다.
2. Vercel에서 저장소를 선택합니다.
3. Framework Preset은 `Other` 또는 정적 사이트로 설정합니다.
4. 별도 Build Command 없이 배포합니다.

### GitHub Pages

1. 저장소 Settings → Pages로 이동합니다.
2. Deploy from a branch를 선택합니다.
3. `main` 브랜치의 `/root`를 선택합니다.

## 반응형 기준

- Desktop: 1180px 콘텐츠 폭
- Tablet: 980px 이하 레이아웃 전환
- Mobile: 680px 이하 카드·표·내비게이션 재구성
- `prefers-reduced-motion` 접근성 대응 포함
