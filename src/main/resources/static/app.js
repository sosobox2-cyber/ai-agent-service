const form = document.querySelector('#inference-form');
const rows = document.querySelector('#option-rows');
const rowTemplate = document.querySelector('#option-row-template');
const resultPanel = document.querySelector('#result-panel');
const resultStatus = document.querySelector('#result-status');
const resultSummary = document.querySelector('#result-summary');
const resultDetails = document.querySelector('#result-details');
const submitButton = document.querySelector('#submit-button');
const resetButton = document.querySelector('#reset-button');
const testModeInput = document.querySelector('#test-mode');
const noticeInput = form.elements.productNoticeText;
const purchaseOptionRows = document.querySelector('#purchase-option-rows');
const purchaseOptionRowTemplate = document.querySelector('#purchase-option-row-template');

const examples = {
  "sample": {
    "goodsId": "68535109",
    "goodsName": "[아이그너]아이그너 레터링 자카드 니트탑",
    "categoryName": "스포츠/레저>스포츠패션/슈즈/아웃도어>스포츠의류(여성)긴팔",
    "allowedPurchaseOptions": [
      "패션의류/잡화 사이즈",
      "색상"
    ],
    "productNoticeText": "1. 제품 소재 : 소재 : 비스코스 레이온 47% + 폴리에스터 27% + 나일론 22% + 폴리우레탄 4%\n2. 색상 : 블랙\n3. 치수 : S(90) / M(95) / L(100) /XL(105)\n4. 제조자,수입품의 경우 수입자를 함께 표기 : ㈜엘케이플래닝 OEM\n5. 제조국: 베트남\n6. 세탁방법 및 취급시 주의사항: 단독손세탁\n7. 제조연월: 2025년 07월\n8. 품질보증기준: 관련 법 및 소비자 분쟁 해결 기준을 따름\n9. A/S 책임자와 전화번호: 유닉유니온/ 070-8656-3977",
    "options": [
      {
        "optionId": "1",
        "optionName1": "블랙/90"
      },
      {
        "optionId": "2",
        "optionName1": "블랙/95"
      },
      {
        "optionId": "3",
        "optionName1": "블랙/100"
      },
      {
        "optionId": "4",
        "optionName1": "블랙/105"
      }
    ]
  },
  "quantity": {
    "goodsId": "64629042",
    "goodsName": "[리르]스마일 링클 패치 9박스 (1박스 10피스*5매)+무료체험분 10피스 1매",
    "categoryName": "뷰티>화장품/헤어/바디>스킨케어팩/마스크",
    "productNoticeText": "005:제조국:한국,008:품질보증기준:관련 법 및 소비자 분쟁 해결 기준을 따름,041:내용물의 용량 또는 중량:스마일 링클패치 9박스(1박스: 5매, 총 50패치)+ 무료체험분 1매(10패치)\n※1매 (미간 패치 x 6, 팔자 패치 x 4 = 총 10패치)\n모양별 개별 중량 0.2g(최외부 지지체 포함), 1매 PET 필름 포함 약 3.5g,042:제품 주요 사양:모든 피부용,043:사용기한 또는 개봉 후 사용기간:제조일로부터 36개월 ,044:사용방법:본품을 피부에 붙이고 8시간 후 떼어낸다.,045:화장품제조업자, 화장품책임판매업자 및 맞춤형 화장품판매업자:㈜코바스 / (주)아이더블유컴퍼니\n,046:「화장품법」에 따라 기재 표시하여야 하는 모든 성분:아크릴레이트코폴리머,1,2-헥산다이올,소듐아크릴레이트/소듐아크릴로일다이메틸타우레이트코폴리머,폴리아이소부텐,글루코노락톤,피브이피,부틸렌글라이콜,정제수,카프릴릴/카프릴글루코사이드,솔비탄올리에이트,아데노신,글리세린,육두구추출물,수크로오스,소듐하이알루로네이트,포타슘하이알루로네이트,베타-글루칸,하이드롤라이즈드옥수수전분,병풀추출물,하이드롤라이즈드소듐하이알루로,047:「화장품법」에 따른 기능성 화장품(미백, 주름개선, 자외선 차단제품 등)의 경우 “화장품법에 따른 기능성 화장품 심사(또는 보고)를 필함”의 문구:피부의 주름개선에 도움을 준다.,048:사용할 때의 주의사항:1) 화장품 사용 시 또는 사용 후 직사광선에 의하여 사용부위가 붉은 반점, 부어오름 또는 가려움증 등의 이상 증상이나 부작용이 있는 경우에는 전문의 등과 상담할 것\n2) 상처가 있는 부위 등에는 사용을 자제할 것\n3) 보관 및 취급시의 주의사항\n 가. 어린이의 손이 닿지 않는 곳에 보관할 것\n 나. 직사광선을 피해서 보관할 것\n4) 눈 주위를 피,049:소비자상담 관련 전화번호:070-4062-3616",
    "allowedPurchaseOptions": [
      "개당 수량",
      "수량"
    ],
    "options": [
      {
        "optionId": "1",
        "optionName1": "단일상품"
      }
    ]
  },
  "capacity": {
    "goodsId": "65578661",
    "goodsName": "[김소형헤밀레](미리주문)(최다구성패키지)김소형 본초순백 비단쌀 안색 선크림 50ml 7개",
    "categoryName": "뷰티>화장품/헤어/바디>썬케어선블록",
    "productNoticeText": "005:제조국:한국,008:품질보증기준:관련 법 및 소비자 분쟁 해결 기준을 따름,041:내용물의 용량 또는 중량:김소형 본초순백 비단쌀 안색 선크림 50ml,042:제품 주요 사양:모든 피부 타입,043:사용기한 또는 개봉 후 사용기간:제조일로부터 36개월까지 (26년 2월 이후 제조)\n\n수시 생산 제품으로 각 상품마다 제조년월일/소비기한(유통기한)/품질유지기한이 상이합니다. 발송될 상품의 정확한 정보를 확인하시려면 판매자에게 문의 바랍니다. (정보고시상 판매자 연락처 참조),044:사용방법:본 품 적당량을 취하여 피부에 고르게 펴 바른다.,045:화장품제조업자, 화장품책임판매업자 및 맞춤형 화장품판매업자:화장품제조업자:유씨엘(주) / 화장품책임판매업자:본초테라피김소형헤밀레(주),046:「화장품법」에 따라 기재 표시하여야 하는 모든 성분:정제수, 쌀수(200,000ppm), 호모살레이트, 부틸렌글라이콜, 비스-에칠헥실옥시페놀메톡시페닐트리아진, 옥토크릴렌, 다이부틸아디페이트, 티타늄디옥사이드, 1,2-헥산다이올, C12-15알킬벤조에이트, 나이아신아마이드, 아이소데케인, 트라이메틸실록시실리케이트, 실리카, 베헤닐알코올, 카프릴릴메티콘, 비닐다이메티콘, 글리세린, 암모늄아크릴로일다이메,047:「화장품법」에 따른 기능성 화장품(미백, 주름개선, 자외선 차단제품 등)의 경우 “화장품법에 따른 기능성 화장품 심사(또는 보고)를 필함”의 문구:자외선 차단(SPF50+ PA++++)+미백+주름개선 3중 기능성 화장품,048:사용할 때의 주의사항:1. 화장품 사용 시 또는 사용 후 직사광선에 의하여 사용부위가 붉은 반점, 부어오름 또는 가려움증 등의 이상 증상이나 부작용이 있는 경우 전문의 등과 상담할 것\n\n2. 상처가 있는 부위 등에는 사용을 자제할 것\n\n3. 보관 및 취급시의 주의사항\n\n가. 어린이의 손이 닿지 않는 곳에 보관할 것\n\n나. 직사광선을 피해서 보관할 것,049:소비자상담 관련 전화번호:고객센터 1566-3393",
    "allowedPurchaseOptions": [
      "개당 용량",
      "수량"
    ],
    "options": [
      {
        "optionId": "1",
        "optionName1": "단일상품"
      }
    ]
  },
  "weight": {
    "goodsId": "65579064",
    "goodsName": "[종가][종가] 전라도 포기김치 4.2kg + 파김치 1kg",
    "categoryName": "식품>가공식품>김치포기김치",
    "productNoticeText": "049:소비자상담 관련 전화번호:080-080-8866,059:제조연월일, 소비기한 또는 품질유지기한:냉장보관 60일,066:식품의 유형:김치,067:생산자 및 소재지, 수입품의 경우 수입자를 함께 표기:대상(주)횡성공장,068:원재료명 (「농수산물의 원산지 표시 등에 관한 법률」에 따른 원산지 표시 포함) 및 함량 (원재료 함량 표시대상 식품에 한함):기타 수산물가공품, 양파, 액젓, 고춧가루, 마늘, 기타가공품, 무, 절임배추, 소스류, 소스류, 당류가공품, 기타가공품, 조미액젓, 갓, 곡류가공품, 액젓, 대파,069:영양성분(영양성분 표시대상 식품에 한함):전라도 포기김치 : 총내용량 4,200g, 100당 45kcal, 나트륨 580mg(29%), 탄수화물 6g(2%), 당류 3g(3%), 지방 1.2g(2%), 트랜스지방 0g, 포화지방 0g(0%), 콜레스테롤 0mg(0%), 단백질 2g(4%),070:유전자변형식품에 해당하는 경우의 표시:해당없음,071:영유아식 또는 체중조절식품 등에 해당하는 경우 표시광고 사전심의필:해당없음,072:포장단위별 내용량의 용량(중량), 수량:전라도 포기김치 4.2kg + 파1kg,157:소비자 안전을 위한 주의사항:- 대두, 밀, 새우, 잣 성분 혼입 가능\n\n- 계절에 따라 쪽파가 실파로 변경 될 수 있습니다. ,158:제품명:종가 칼칼하고 진하게 깊은 맛 전라도 포기김치,179:수입식품의 경우 \"수입식품안전관리 특별법에 따른 수입신고를 필함\" 의 문구:해당없음,185:품목제조보고번호:-",
    "allowedPurchaseOptions": [
      "개당 중량",
      "수량"
    ],
    "options": [
      {
        "optionId": "1",
        "optionName1": "단일상품"
      }
    ]
  },
  "set": {
    "goodsId": "65592697",
    "goodsName": "[해즈픽]시그니처 케어 칫솔 10개",
    "categoryName": "생활용품>욕실용품>구강용품/타월/목욕칫솔/치실",
    "productNoticeText": "004:제조자,수입품의 경우 수입자를 함께 표기:(주)케이앤케이 ,019:품명 및 모델명:[해즈픽] 시그니처 케어 칫솔 10개 세트 \n*컬러: 민트, 핑크, 오렌지, 그레이, 블루 2개씩,049:소비자상담 관련 전화번호:070-4027-4667,126:법에 의한 인증·허가 등을 받았음을 확인할 수 있는 경우 그에 대한 사항:위생용품의 유형 : 일반용 칫솔,127:제조국 또는 원산지:한국",
    "allowedPurchaseOptions": [
      "개당 수량",
      "수량"
    ],
    "options": [
      {
        "optionId": "1",
        "optionName1": "단일상품"
      }
    ]
  }
};

examples.kitchenTowel = {
  "goodsId": "20041302",
  "goodsName": "[크리넥스]크리넥스 KLX 안심 클래식 키친타올 140매x12롤",
  "categoryName": "생활용품>생활/건강/욕실/애완>화장지/각티슈/주방랩키친타올",
  "productNoticeText": "004:제조자,수입품의 경우 수입자를 함께 표기:유한킴벌리 ,019:품명 및 모델명:크리넥스 키친타올,049:소비자상담 관련 전화번호:유한킴벌리 고객센터 : 080-022-7007,126:법에 의한 인증·허가 등을 받았음을 확인할 수 있는 경우 그에 대한 사항:해당없음,127:제조국 또는 원산지:한국\n",
  "allowedPurchaseOptions": [
    "개당 수량",
    "수량"
  ],
  "purchaseOptionUnits": [
    {
      "purchaseOptionName": "개당 수량",
      "defaultUnit": "개",
      "unitOptions": [
        "개입",
        "롤",
        "매",
        "매입",
        "세트"
      ]
    },
    {
      "purchaseOptionName": "수량",
      "defaultUnit": "개",
      "unitOptions": [
        "개",
        "박스",
        "세트"
      ]
    }
  ],
  "options": [
    {
      "optionId": "1",
      "optionName1": "단일상품"
    }
  ]
};

examples.pillow = {
  "goodsId": "64512579",
  "goodsName": "[KoDanZam]코 단잠 초특가 냉감 메밀베개 4개 세트",
  "categoryName": "가구/침구>침구/인테리어>침구단품/세트베개/베개커버",
  "productNoticeText": "\"001:제품 소재:[겉커버]\n겉감1:폴리에스터70%,폴리에틸렌30%\n겉감2:폴리에스터100%\n겉감3:폴리에스터100%\n[속통]\n겉감:나일론100%\n충전재:메밀껍질\n,002:색상:화이트, 그레이,003:치수:43*25cm(+-3cm),004:제조자,수입품의 경우 수입자를 함께 표기:(주)단잠코리아\n,005:제조국:대한민국,006:세탁방법 및 취급시 주의사항:- 찬물에서 단독 기계 세탁 가능(건조기 사용 불가, 겉커버를 따로 분리하여 세탁할 것, 속통은 겉감만 따로 분리하여 세탁할 것, 충전재(메밀껍질)는 세탁 불가)\n- 기계 세탁 가능(물 세탁, 세탁망 사용)\n- 개별 단독 세탁 권장, 표백제 사용 금지, 다림질 금지\n- 기계 세탁의 경우 30℃ 이하의 미온수에서 중성 세제로 세탁 가능\n-.탈수는 가,008:품질보증기준:관련 법 및 소비자 분쟁 해결 기준을 따름,009:A/S 책임자와 전화번호:(주)단잠코리아 010-3234-3548 ,013:제품구성:냉감 메밀베개 4개\"\n",
  "productCompositionText": "\"냉감메밀베개 4개\n\"\n\n",
  "allowedPurchaseOptions": [
    "색상",
    "사이즈",
    "수량"
  ],
  "purchaseOptionUnits": [
    {
      "purchaseOptionName": "수량",
      "defaultUnit": "개",
      "unitOptions": [
        "개",
        "세트"
      ]
    }
  ],
  "options": [
    {
      "optionId": "1",
      "optionName1": "화이트"
    },
    {
      "optionId": "2",
      "optionName1": "그레이"
    }
  ]
};

examples.smartboard = {
  "goodsId": "65462825",
  "goodsName": "[LG전자]LG 75인치 전자칠판 75TR3DQ 안드로이드 스마트보드 [수도권설치]이동형스탠드(CA-86)",
  "categoryName": "가전/디지털>영상/주방/생활/계절가전>영상가전LED TV",
  "productNoticeText": "004:제조자,수입품의 경우 수입자를 함께 표기:LG전자,005:제조국:중국,008:품질보증기준:1년 무상A/S,009:A/S 책임자와 전화번호:1544-7777,011:크기:1709x1031x100mm,019:품명 및 모델명:75TR3DQ,020:KC 인증정보 (「전기용품 및 생활용품 안전관리법」에 따른 안전인증ㆍ안전확인ㆍ공급자적합성확인대상제품 및 「전파법」에 따른 적합인증ㆍ적합등록 대상 기자재에 한함):R-R-LGE-75TR3DJ-B,022:동일모델의 출시년월:2025.03,023:화면사양 (화면크기, 해상도, 화면비율 등):3840x2160 (UHD),029:정격전압, 소비전력:100-240V/50~60Hz /175W~365W,144:에너지 소비효율등급:해당없음,178:추가설치비용:벽걸이 설치의 경우 벽면 수준에 따라 추가금액\n",
  "allowedPurchaseOptions": [
    "화면크기 (cm/(인치))",
    "모델명/품번",
    "스탠드/벽걸이 구분"
  ],
  "options": [
    {
      "optionId": "1",
      "optionName1": "단일상품"
    }
  ]
};

examples.seafood = {
  "goodsId": "65464903",
  "goodsName": "[올레마켓]올레마켓 제주 통옥돔 160g*5미(총, 800g)",
  "categoryName": "식품>신선식품>생선/해산물옥돔",
  "productNoticeText": "\"049:소비자상담 관련 전화번호:064-762-9531,056:포장단위별 내용물의 용량(중량), 수량:제주 통옥돔 160g*5미(총, 800g)\n(제품 특성상 토막당 중량 표기는 어렵습니다.)\n,057:생산자, 수입품의 경우 수입자를 함께 표기:제조원 : 올레마켓\n,058:「농수산물의 원산지 표시 등에 관한 법률」에 따른 원산지:갈치 100%(국내산)\n,060:농수산물 - 「농수산물 품질관리법」에 따른 유전자변형농수산물 표시, 지리적 표시:해당없음,061:축산물 - 축산법에 따른 등급 표시:해당없음,063:수입 농수축산물에 해당하는 경우 “수입식품안전관리특별법에 따른 수입신고를 필함”의 문구:해당없음,064:상품구성:제주 통옥돔 160g*5미(총, 800g),065:보관방법 또는 취급방법:-18도 이하 냉동보관\n,154:품목 또는 명칭:제주 옥돔,155:제조연월일, 소비기한 또는 품질유지기한:제조년월 : 상시제조 (2026. 6월 이후)\n소비기한 : 제조일로부터 24개월\n,156:소비자 안전을 위한 주의사항 (「식품 등의 표시ㆍ광고에 관한 법률 시행규칙」 제5조 및 [별표 2]에 따른 표시사항을 말함):본 제품은 고등어, 새우, 오징어를 사용한 제품과 동일한 생산시설에서 제조되었습니다.\n\n자연해동 후 가열하여 섭취하기시 바랍니다.\n\n개봉한 제품은 변질되기 쉬우니 바로 드시기 바랍니다.\"\n",
  "productCompositionText": "제주 통옥돔 160g*5미(총, 800g)\n",
  "allowedPurchaseOptions": [
    "수량",
    "수산물 중량"
  ],
  "options": [
    {
      "optionId": "1",
      "optionName1": "단일상품"
    }
  ]
};

examples.cream = {
  "goodsId": "69358893",
  "goodsName": "[스와니브][SK단독구성] 특대용량 블랙 캐비어 트러플 영양크림 120g 3통+무료체험 50g 1개+쇼핑백",
  "categoryName": "뷰티>화장품/헤어/바디>스킨케어크림(영양/탄력/리프팅)",
  "productNoticeText": "\"005:제조국:한국,008:품질보증기준:본 상품에 이상이 있을 경우, 공정거래위원회 고시 '소비자 분쟁 해결 기준'에 의해 보상해드립니다.,041:내용물의 용량 또는 중량:120g / 50g,042:제품 주요 사양:모든 피부 사용 가능 ,043:사용기한 또는 개봉 후 사용기간:제조일로부터 36개월, 개봉 후 12개월 ,044:사용방법:본품 적당량을 취해 피부에 골고루 펴 바른다.,045:화장품제조업자, 화장품책임판매업자 및 맞춤형 화장품판매업자:화장품제조원/책임판매원 : 주식회사코스엘 / 주식회사코스엘,046:「화장품법」에 따라 기재 표시하여야 하는 모든 성분:상세페이지 참조 ,047:「화장품법」에 따른 기능성 화장품(미백, 주름개선, 자외선 차단제품 등)의 경우 “화장품법에 따른 기능성 화장품 심사(또는 보고)를 필함”의 문구:피부의 미백에 도움을 준다. 피부의 주름개선에 도움을 준다.,048:사용할 때의 주의사항:1. 화장품 사용 시 또는 사용 후 직사광선에 의하여 사용부위가 붉은 반점, 부어오름 또는 가려움증 등의 이상 증상이나 부작용이 있는 경우 전문의 등과 상담할 것\n2. 상처가 있는 부위 등에는 사용을 자제할 것\n3. 보관 및 취급시의 주의사항\n\t   가. 어린이의 손이 닿지 않는 곳에 보관할 것\n\t   나. 직사광선을 피해서 보관할 것,049:소비자상담 관련 전화번호: 031-991-2024\"\n",
  "productCompositionText": "스와니브 블랙 트러플 캐비어 영양크림 특대용량 120g 3통 + 무료체험분 50g 1통 + 쇼핑백\n",
  "allowedPurchaseOptions": [
    "개당 중량",
    "수량"
  ],
  "purchaseOptionUnits": [
    {
      "purchaseOptionName": "개당 중량",
      "defaultUnit": "g",
      "unitOptions": [
        "g",
        "kg"
      ]
    },
    {
      "purchaseOptionName": "수량",
      "defaultUnit": "개",
      "unitOptions": [
        "개"
      ]
    }
  ],
  "options": [
    {
      "optionId": "1",
      "optionName1": "단일상품"
    }
  ]
};

examples.toiletPaper = {
  goodsId: '69356512',
  goodsName: '[모나리자]모나리자 홈앤코튼 시그니처 22m x 30롤 x 4팩(총 120롤)',
  categoryName: '생활용품>생활/건강/욕실/애완>화장지/각티슈/주방랩화장지',
  productNoticeText: '004:제조자,수입품의 경우 수입자를 함께 표기:모나리자,019:품명 및 모델명:모나리자 홈앤코튼 시그니처 22m x 30롤 x 4팩(총 120롤),049:소비자상담 관련 전화번호:080-024-4698,126:법에 의한 인증·허가 등을 받았음을 확인할 수 있는 경우 그에 대한 사항:해당없음,127:제조국 또는 원산지:한국\n',
  productCompositionText: '모나리자 홈앤코튼 시그니처 22m x 30롤 x 4팩(총 120롤)\n',
  allowedPurchaseOptions: ['개당 수량', '길이', '수량'],
  purchaseOptionUnits: [
    { purchaseOptionName: '개당 수량', defaultUnit: '개', unitOptions: ['개입', '롤', '매', '매입', '세트'] },
    { purchaseOptionName: '길이', defaultUnit: 'cm', unitOptions: ['cm', 'm', 'mm'] },
    { purchaseOptionName: '수량', defaultUnit: '개', unitOptions: ['개', '박스', '세트'] }
  ],
  options: [{ optionId: '1', optionName1: '단일상품' }]
};

examples.tv = {
  "goodsId": "50945478",
  "goodsName": "[플럭스][5년무상AS]플럭스 109cm(43인치) 이동형 QLED TV (셀프설치)",
  "categoryName": "가전/디지털>영상/주방/생활/계절가전>영상가전LED TV",
  "productNoticeText": "004:제조자,수입품의 경우 수입자를 함께 표기:상품상세내용참조,005:제조국:상품상세내용참조,008:품질보증기준:소비자분쟁해결 기준에 따름,009:A/S 책임자와 전화번호:주식회사미래가디언 031-812-3020,011:크기:957 X 206 X 604,019:품명 및 모델명:TV, PLX-43UHWH,020:KC 인증정보 (「전기용품 및 생활용품 안전관리법」에 따른 안전인증ㆍ안전확인ㆍ공급자적합성확인대상제품 및 「전파법」에 따른 적합인증ㆍ적합등록 대상 기자재에 한함):상품상세내용참조,022:동일모델의 출시년월:202503,023:화면사양 (화면크기, 해상도, 화면비율 등):QLED UHD(4K : 3840*2160), DOLBY ATMOS, .,029:정격전압, 소비전력:220V, 75W,144:에너지소비효율등급 (「에너지이용 합리화법」에 따른 에너지소비효율등급 표시대상 기자재에 한함):1등급,178:추가설치비용:0",
  "allowedPurchaseOptions": [
    "화면크기(in)", "화면크기(cm)", "설치지원방식", "스탠드/벽걸이 구분", "모델명/품번", "화면크기 (cm/(인치))"
  ],
  "options": [{ "optionId": "1", "optionName1": "단품" }]
};

examples.quantity.purchaseOptionUnits = [
  { purchaseOptionName: '수량', defaultUnit: '개', unitOptions: ['개'] },
  { purchaseOptionName: '개당 수량', defaultUnit: '개', unitOptions: ['개입', '롤', '매', '매입', '세트'] }
];

examples.set.purchaseOptionUnits = [
  { purchaseOptionName: '수량', defaultUnit: '개', unitOptions: ['개', '박스', '세트'] },
  { purchaseOptionName: '개당 수량', defaultUnit: '개', unitOptions: ['개입', '롤', '매', '매입', '세트'] }
];

examples.capacity.purchaseOptionUnits = [
  { purchaseOptionName: '수량', defaultUnit: '개', unitOptions: ['개', '박스', '세트'] },
  { purchaseOptionName: '개당 용량', defaultUnit: 'ml', unitOptions: ['ml', 'L'] }
];

examples.weight.purchaseOptionUnits = [
  { purchaseOptionName: '수량', defaultUnit: '개', unitOptions: ['개', '박스', '세트'] },
  { purchaseOptionName: '개당 중량', defaultUnit: 'kg', unitOptions: ['kg', 'g', 'mg'] }
];

function addPurchaseOptionRow(option = {}) {
  if (purchaseOptionRows.children.length >= 200) {
    showMessage('구매옵션은 최대 200개까지 입력할 수 있습니다.');
    return;
  }
  const row = purchaseOptionRowTemplate.content.firstElementChild.cloneNode(true);
  row.querySelectorAll('[data-purchase-field]').forEach(input => {
    const field = input.dataset.purchaseField;
    input.value = field === 'unitOptions' ? (option.unitOptions || []).join(', ') : option[field] || '';
  });
  row.querySelector('.remove-row').addEventListener('click', () => {
    if (purchaseOptionRows.children.length > 1) row.remove();
    else row.querySelectorAll('input').forEach(input => { input.value = ''; });
  });
  purchaseOptionRows.append(row);
}
function nextOptionId() {
  let maxId = 0n;
  rows.querySelectorAll('[data-field="optionId"]').forEach(input => {
    const value = input.value.trim();
    if (/^\d+$/.test(value)) {
      const id = BigInt(value);
      if (id > maxId) maxId = id;
    }
  });
  return String(maxId + 1n);
}

function addRow(option = {}) {
  if (rows.children.length >= 200) {
    showMessage('단품은 최대 200개까지 입력할 수 있습니다.');
    return;
  }
  const row = rowTemplate.content.firstElementChild.cloneNode(true);
  const optionId = option.optionId ?? nextOptionId();
  row.querySelectorAll('[data-field]').forEach(input => {
    input.value = input.dataset.field === 'optionId' ? optionId : option[input.dataset.field] ?? '';
  });
  row.querySelector('.remove-row').addEventListener('click', () => {
    if (rows.children.length > 1) row.remove();
    else row.querySelectorAll('input').forEach(input => {
      if (input.dataset.field !== 'optionId') input.value = '';
    });
  });
  rows.append(row);
}

function resizeNoticeInput() {
  noticeInput.style.height = '0px';
  const borders = noticeInput.offsetHeight - noticeInput.clientHeight;
  noticeInput.style.height = `${noticeInput.scrollHeight + borders}px`;
}

function resetInputs() {
  form.reset();
  testModeInput.checked = false;
  rows.replaceChildren();
  purchaseOptionRows.replaceChildren();
  addRow();
  addPurchaseOptionRow();
  document.querySelector('.composition-details').open = true;
  resultPanel.hidden = true;
  resultStatus.textContent = '';
  resultSummary.textContent = '';
  resultDetails.replaceChildren();
  for (const selector of ['#request-json', '#response-json', '#copy-json-status']) {
    document.querySelector(selector).textContent = '';
  }
  resizeNoticeInput();
}

function loadExample(example) {
  testModeInput.checked = true;
  for (const field of ['goodsId', 'goodsName', 'categoryName', 'productNoticeText', 'productCompositionText']) {
    form.elements[field].value = example[field] ?? '';
  }
  purchaseOptionRows.replaceChildren();
  const unitsByName = new Map((example.purchaseOptionUnits || []).map(units => [units.purchaseOptionName, units]));
  example.allowedPurchaseOptions.forEach(name => {
    addPurchaseOptionRow({ purchaseOptionName: name, ...unitsByName.get(name) });
  });
  rows.replaceChildren();
  example.options.forEach(addRow);
  resultPanel.hidden = true;
  resizeNoticeInput();
}

function splitNames(text) {
  return text.split(/[,\r\n]+/).map(name => name.trim()).filter(Boolean);
}

function makeRequest() {
  const request = {};
  for (const field of ['goodsId', 'goodsName']) {
    request[field] = form.elements[field].value.trim();
  }
  const categoryName = form.elements.categoryName.value.trim();
  if (categoryName) request.categoryName = categoryName;
  const productNoticeText = form.elements.productNoticeText.value;
  request.productNoticeText = productNoticeText;
  const productCompositionText = form.elements.productCompositionText.value;
  if (productCompositionText.trim()) request.productCompositionText = productCompositionText;
  const purchaseOptions = [...purchaseOptionRows.querySelectorAll('tr')].map(row => {
    const values = {};
    row.querySelectorAll('[data-purchase-field]').forEach(input => {
      values[input.dataset.purchaseField] = input.dataset.purchaseField === 'unitOptions'
        ? splitNames(input.value) : input.value.trim();
    });
    return values;
  });
  request.allowedPurchaseOptions = purchaseOptions.map(option => option.purchaseOptionName);
  const units = purchaseOptions.filter(option => option.defaultUnit !== '없음'
    && (option.defaultUnit || option.unitOptions.length));
  if (units.length) request.purchaseOptionUnits = units;
  request.options = [...rows.querySelectorAll('tr')].map(row => {
    const option = {};
    row.querySelectorAll('[data-field]').forEach(input => {
      const value = input.value;
      if (value.trim()) option[input.dataset.field] = input.dataset.field === 'optionId' ? value.trim() : value;
    });
    return option;
  });
  if (request.options.every(option => !option.optionName1?.trim())) {
    const optionId = request.options.length === 1 ? request.options[0].optionId : null;
    request.options = [{ optionId: optionId || '1', optionName1: '단일상품' }];
  }
  return request;
}

function validateRequest(request) {
  if (!request.productNoticeText?.trim()) return 'SK스토아 상품정보고시를 입력하세요.';
  if (request.allowedPurchaseOptions.length < 1) return '허용 옵션명을 한 개 이상 입력하세요.';
  if (request.allowedPurchaseOptions.length > 200) return '구매옵션명은 최대 200개입니다.';
  if (request.allowedPurchaseOptions.some(name => !name || name.length > 100)) return '각 구매옵션명을 1~100자로 입력하세요.';
  if (new Set(request.allowedPurchaseOptions).size !== request.allowedPurchaseOptions.length) return '구매옵션명이 중복되었습니다.';
  const unitNames = new Set();
  for (const units of request.purchaseOptionUnits || []) {
    if (!request.allowedPurchaseOptions.includes(units.purchaseOptionName)) return '단위를 설정할 구매옵션명을 입력하세요.';
    if (unitNames.has(units.purchaseOptionName)) return '같은 구매옵션명의 단위 설정은 한 번만 입력하세요.';
    unitNames.add(units.purchaseOptionName);
    if (!units.defaultUnit) return '기본단위를 입력하세요.';
    if (units.unitOptions.length < 1) return '단위 선택지를 한 개 이상 입력하세요.';
    if (units.unitOptions.length > 30 || units.unitOptions.some(unit => unit.length > 30)) return '단위 선택지는 최대 30개이며 각 단위는 30자 이내입니다.';
    if (new Set(units.unitOptions).size !== units.unitOptions.length) return '단위 선택지가 중복되었습니다.';
  }
  if (request.options.some(option => !option.optionName1?.trim())) {
    return '각 단품의 원본 옵션명을 입력하세요.';
  }
  if (request.options.some(option => !option.optionId?.trim())) return '각 단품의 ID를 입력하세요.';
  return null;
}

function element(tag, value, className) {
  const node = document.createElement(tag);
  node.textContent = value ?? '';
  if (className) node.className = className;
  return node;
}

function table(headers, rowsData) {
  const wrapper = element('div', '', 'table-scroll');
  const tableNode = element('table', '', 'result-table');
  const head = document.createElement('thead');
  const headRow = document.createElement('tr');
  headers.forEach(header => headRow.append(element('th', header)));
  head.append(headRow);
  const body = document.createElement('tbody');
  rowsData.forEach(cells => {
    const row = document.createElement('tr');
    cells.forEach(cell => row.append(element('td', cell)));
    body.append(row);
  });
  tableNode.append(head, body);
  wrapper.append(tableNode);
  return wrapper;
}

function calculationText(calculation) {
  if (!calculation) return '원문 추출';
  const names = { DIRECT: '직접 추출', CONVERT: '단위 환산', SUM: '합산', PACK_COUNT: '판매 묶음 수', PACK_CONTENT: '묶음 내 수량' };
  const operands = (calculation.operands || []).map(operand => operand ? `${operand.amount}${operand.unit}` : '누락').join(' + ');
  const context = calculation.context;
  const source = { goodsName: '상품명', productNoticeText: '정보고시', productCompositionText: '기술서 구성', optionName1: '원본 옵션명' }[context?.source] || context?.source;
  return `${names[calculation.operation] || calculation.operation} · ${operands || '구성 1묶음'} → ${calculation.outputUnit}`
    + (context?.text ? ` · ${source}: ${context.text}` : '');
}

function summarizeAiUsage(calls) {
  const summary = {};
  for (const field of ['input_tokens', 'cached_tokens', 'output_tokens', 'total_tokens', 'estimated_cost_usd']) {
    summary[field] = calls.length && calls.every(call => Number.isFinite(call?.[field]) && call[field] >= 0)
      ? calls.reduce((total, call) => total + call[field], 0) : null;
  }
  return summary;
}

function aiUsagePanel(response) {
  const calls = Array.isArray(response?.aiUsage) ? response.aiUsage : [];
  const details = element('details', '', 'usage-details');
  const modes = [...new Set(calls.map(call => call.mode))].join(' / ');
  details.append(element('summary', `AI 호출·토큰 사용량${calls.length ? ` · ${modes} · ${calls.length}회` : ''}`));
  if (!calls.length) {
    details.append(element('p', response?.inferenceSource === 'TEST'
      ? '테스트 모드는 실제 AI를 호출하지 않으므로 토큰 사용량과 비용이 없습니다.'
      : '이 응답에는 AI 호출 사용량 정보가 없습니다.', 'usage-note'));
    return details;
  }
  const number = value => Number.isFinite(value) && value >= 0 ? value.toLocaleString('ko-KR') : '확인 불가';
  const cost = value => Number.isFinite(value) && value >= 0 ? `$${value.toFixed(8)}` : '확인 불가';
  const values = call => [number(call.input_tokens), number(call.cached_tokens), number(call.output_tokens),
    number(call.total_tokens), cost(call.estimated_cost_usd)];
  const usageRows = calls.map(call => [call.attempt === 1 ? '최초 호출' : `보정 재요청 (${call.attempt}차)`,
    call.mode, call.model, ...values(call)]);
  if (calls.length > 1) {
    usageRows.push(['요청 전체 합계', '—', '—', ...values(summarizeAiUsage(calls))]);
  }
  details.append(table(['호출', '모드', '모델', '입력 토큰', '캐시 입력', '출력 토큰', '총 토큰', '예상 비용 (USD)'], usageRows),
    element('p', '캐시 입력은 입력 토큰에 포함됩니다. '
      + (calls.length > 1 ? '합계에는 보정 재요청도 포함됩니다. ' : '')
      + '예상 비용은 지원 모델의 단가로 계산하며, 제공되지 않은 수치는 확인 불가로 표시합니다.', 'usage-note'));
  return details;
}

function showResult(request, response, httpStatus) {
  resultPanel.hidden = false;
  resultDetails.replaceChildren();
  document.querySelector('#copy-json-status').textContent = '';
  const success = response?.success === true;
  const simulated = response?.inferenceSource === 'TEST' && response?.errorCode === 'TEST_MODE';
  resultStatus.className = `status ${simulated ? 'review' : success ? 'success' : httpStatus === 0 || httpStatus >= 400 ? 'error' : 'review'}`;
  resultStatus.textContent = simulated ? '테스트 결과 · 모의' : success ? '매핑 성공' : httpStatus === 0 ? '연결 오류' : httpStatus >= 400 ? `요청 오류 · HTTP ${httpStatus}` : '검토 필요';
  resultSummary.textContent = simulated ? '모의 추출 결과입니다. 자동 적용할 수 없습니다.'
    : success ? '단품별 최종 구매옵션을 확인하세요.' : response?.serverAssessment?.reason || response?.reason || '결과를 확인하세요.';
  const finalResult = element('section', '', `final-result ${simulated ? 'simulated' : success ? 'approved' : 'unavailable'}`);
  const finalHeading = element('div', '', 'final-result-heading');
  finalHeading.append(element('h3', simulated ? '단품 결과 · 모의 테스트' : '최종 단품 결과'),
    element('span', response?.items?.length ? `${response.items.length}개 단품` : '결과 없음', 'final-result-count'));
  finalResult.append(finalHeading);
  if (response?.items?.length) {
    const names = [...new Set(response.items.flatMap(item => Object.keys(item.purchaseOptions || {})))];
    finalResult.append(table(['단품 ID', ...names], response.items.map(item => [
      item.optionId, ...names.map(name => item.purchaseOptions?.[name] ?? '—')
    ])));
  } else {
    finalResult.append(element('p', '확정된 단품 결과가 없습니다. 아래 확인할 항목과 판단 상세를 확인하세요.', 'final-result-empty'));
  }
  resultDetails.append(finalResult);
  resultDetails.append(aiUsagePanel(response));
  const diagnostics = element('details', '', 'diagnostic-details');
  diagnostics.open = !success && !simulated;
  diagnostics.append(element('summary', 'AI·서버 판단 및 옵션 매핑 상세'));
  const metrics = element('div', '', 'metrics');
  for (const [label, value] of [
    ['추론 방식', response?.inferenceSource ?? '—'],
    ['신뢰도', simulated ? '측정 안 함' : typeof response?.confidence === 'number' ? `${(response.confidence * 100).toFixed(1)}%` : '—'],
    ['자동 적용 후보', response?.autoApplyCandidate ? '예' : '아니요'],
    ['오류 코드', response?.errorCode ?? '없음']
  ]) {
    const metric = element('div', '', 'metric');
    metric.append(element('span', label), element('strong', value));
    metrics.append(metric);
  }
  diagnostics.append(metrics);
  const ai = response?.aiAssessment;
  if (ai) {
    const section = element('section', '', 'assessment');
    section.append(element('h3', 'AI 판단 · 서버 승인 전 제안'));
    const certainty = ai.certain === true ? '확실함' : ai.certain === false ? '불확실함' : '판정 없음';
    const confidence = typeof ai.confidence === 'number' ? ai.confidence.toFixed(2) : '—';
    section.append(element('small', `AI 확실 판정: ${certainty} · AI 전체 신뢰도: ${confidence}`));
    section.append(element('p', ai.reason || 'AI 판단 사유가 없습니다.', 'ai-reason'));
    if (!success) section.append(element('p', '아래 제안은 서버가 승인하지 않은 값입니다. 검토용으로 표시합니다.'));
    if (ai.mappings?.length) {
      section.append(table(['단품 ID', '구매옵션명', 'AI 제안 값', '개별 신뢰도', 'AI가 제출한 근거', '판단 구조'], ai.mappings.map(mapping => {
        if (!mapping) return ['—', '—', '누락된 제안', '—', '—', '—'];
        const source = { goodsName: '상품명', productNoticeText: '정보고시', productCompositionText: '기술서 구성' }[mapping.evidenceSource] || mapping.evidenceSource || '원본 옵션명';
        return [mapping.optionId, mapping.targetPurchaseOptionName, mapping.value,
          typeof mapping.confidence === 'number' ? mapping.confidence.toFixed(2) : '—',
          mapping.evidenceText ? `${source}: ${mapping.evidenceText}` : source, calculationText(mapping.calculation)];
      })));
    } else section.append(element('p', 'AI가 제안한 매핑이 없습니다.'));
    diagnostics.append(section);
  } else if (response?.serverAssessment) {
    diagnostics.append(element('p', simulated ? '테스트 모드이므로 실제 AI 판단 데이터는 없습니다.' : '확인할 수 있는 AI 판단 데이터가 없습니다.', 'result-summary'));
  }
  if (response?.serverAssessment) {
    const server = response.serverAssessment;
    const section = element('section', '', 'assessment');
    section.append(element('h3', '서버 판정'), element('p', server.reason));
    const threshold = typeof server.confidenceThreshold === 'number' ? server.confidenceThreshold.toFixed(2) : '—';
    section.append(element('small', `판정 코드: ${server.decisionCode} · 신뢰도 기준: ${threshold}`));
    diagnostics.append(section);
  }
  if (response?.validationErrors?.length) {
    resultDetails.append(element('h3', '확인할 항목'));
    const list = element('ul', '', 'validation-errors');
    response.validationErrors.forEach(error => list.append(element('li', error)));
    resultDetails.append(list);
  }
  if (response?.optionMappings?.length) {
    diagnostics.append(element('h3', simulated ? '모의 옵션명 매핑' : '서버 승인 옵션명 매핑'));
    const hasEvidence = response.optionMappings.some(mapping => mapping.evidenceText);
    const hasCalculation = response.optionMappings.some(mapping => mapping.calculation);
    const headers = ['단품 ID', '원본 옵션명', '쿠팡 구매옵션명', '추출 값', '신뢰도'];
    if (hasEvidence) headers.push('판단 근거');
    if (hasCalculation) headers.push('서버가 검증한 구조');
    diagnostics.append(table(headers, response.optionMappings.map(mapping => [
      mapping.optionId, mapping.sourceOptionName || '(이름 없음)', mapping.targetPurchaseOptionName, mapping.value,
      simulated ? '—' : `${(mapping.confidence * 100).toFixed(1)}%`,
      ...(hasEvidence ? [mapping.evidenceText ? `${{ goodsName: '상품명', productNoticeText: '정보고시', productCompositionText: '기술서 구성', optionName1: '원본 옵션명' }[mapping.evidenceSource] || mapping.evidenceSource}: ${mapping.evidenceText}` : '원본 옵션명'] : []),
      ...(hasCalculation ? [calculationText(mapping.calculation)] : [])
    ])));
  }
  resultDetails.append(diagnostics);
  document.querySelector('#request-json').textContent = JSON.stringify(request, null, 2);
  document.querySelector('#response-json').textContent = JSON.stringify(response, null, 2);
  resultPanel.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

async function copyJson(button) {
  const status = document.querySelector('#copy-json-status');
  const value = document.querySelector(`#${button.dataset.copyTarget}`).textContent;
  if (!value) { status.textContent = '복사할 JSON이 없습니다.'; return; }
  button.disabled = true;
  try {
    let copied = false;
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
        copied = true;
      }
    } catch { /* 클립보드 API를 사용할 수 없으면 브라우저 복사 기능을 사용한다. */ }
    if (!copied) {
      const previousFocus = document.activeElement;
      const buffer = document.createElement('textarea');
      buffer.value = value;
      buffer.setAttribute('aria-label', 'JSON 복사용 임시 입력');
      buffer.style.position = 'fixed';
      buffer.style.left = '-9999px';
      document.body.append(buffer);
      try {
        buffer.select();
        copied = document.execCommand('copy');
      } finally {
        buffer.remove();
        previousFocus?.focus();
      }
    }
    status.textContent = copied ? `${button.dataset.copyLabel} JSON을 복사했습니다.`
      : '복사하지 못했습니다. JSON을 직접 선택해 복사해주세요.';
  } catch {
    status.textContent = '복사하지 못했습니다. JSON을 직접 선택해 복사해주세요.';
  } finally {
    button.disabled = false;
  }
}

document.querySelectorAll('[data-copy-target]').forEach(button => {
  button.addEventListener('click', () => copyJson(button));
});

function showMessage(message) {
  showResult(makeRequest(), { success: false, reason: message, errorCode: 'INPUT_ERROR' }, 400);
}

noticeInput.addEventListener('input', resizeNoticeInput);
resetButton.addEventListener('click', resetInputs);
window.addEventListener('resize', resizeNoticeInput);
document.querySelector('#add-row').addEventListener('click', () => addRow());
document.querySelector('#add-purchase-option-row').addEventListener('click', () => addPurchaseOptionRow());
document.querySelectorAll('[data-example]').forEach(button => {
  button.addEventListener('click', () => loadExample(examples[button.dataset.example]));
});
form.addEventListener('submit', async event => {
  event.preventDefault();
  const request = makeRequest();
  const validationError = validateRequest(request);
  if (validationError) { showMessage(validationError); return; }
  submitButton.disabled = true;
  resetButton.disabled = true;
  submitButton.textContent = '처리 중…';
  try {
    const url = `/api/v1/coupang/purchase-options/infer${testModeInput.checked ? '?testMode=true' : ''}`;
    const response = await fetch(url, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request)
    });
    const body = await response.json();
    showResult(request, body, response.status);
  } catch (error) {
    showResult(request, { success: false, reason: '서버에 연결할 수 없거나 응답을 읽지 못했습니다.', errorCode: 'NETWORK_ERROR' }, 0);
  } finally {
    submitButton.disabled = false;
    resetButton.disabled = false;
    submitButton.textContent = 'AI 매핑 결과 ->';
  }
});

resetInputs();
