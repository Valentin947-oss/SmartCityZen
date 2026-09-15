# Smart CityZen — Пристапност мапа + рутирање (Android MVP)

Kotlin Android проект, ист стек како ParkMK (OSMDroid, Firebase, MVVM, Coroutines),
но фокусиран на функциите од B2C слајдот: мапирање пречки/рампи, community
верификација и **пристапна рута од А до Б** што ги избегнува дупките и
непристапните тротоари.

## Како работи рутирањето (најважниот дел)

1. **`OsmGraphBuilder`** повлекува вистинска пешачка мрежа за Битола преку
   Overpass API (footway/path/pedestrian/sidewalk/crossing patterns) и ја
   претвора во граф: секој OSM јазол = `GraphNode`, секој сегмент од патека
   помеѓу два јазли = `GraphEdge` со тежина = растојание во метри.
2. Секој пат кога се бара рута, **`AccessibleRouteFinder.applyAccessibilityWeights()`**
   ги минува сите активни `AccessibilityReport` од Firestore и за секој edge
   чиј среден дел е во радиус од ~12м од пријава, ја множи тежината со
   `ReportType.basePenalty`:
   - `NO_RAMP` / `POTHOLE` / `BLOCKED_PATH` → многу поскап пат (алгоритмот
     го избегнува, освен ако е единствениот можен пат)
   - `VERIFIED_RAMP` / `ACCESSIBLE_ENTRANCE` → поевтин пат (алгоритмот
     активно го преферира)
3. **A\*** пребарување (стандардна имплементација со haversine heuristic)
   го наоѓа најефтиниот пат низ така претежинетиот граф — тоа е "најдобрата
   траса" бараше во спецификацијата.

Ова е реален routing engine, не симулација — сепак, точноста зависи од
густината на OSM податоците за тротоари во Битола (тие честопати недостасуваат
за помали улици), и токму затоа crowdsourcing делот (граѓани пријавуваат
пречки) е клучен за да се пополнат празнините што OSM ги нема.

## Структура

```
app/src/main/java/mk/smartcityzen/app/
  model/AccessibilityReport.kt   — тип на пречка/помагало + тежина за рутирање
  data/ReportRepository.kt       — Firestore CRUD + realtime listener + verify(+10 поени)
  routing/Graph.kt                — GraphNode/GraphEdge/PedestrianGraph
  routing/OsmGraphBuilder.kt      — Overpass API → граф на пешачка мрежа
  routing/AccessibleRouteFinder.kt— A* со accessibility-aware тежини
  ui/MapViewModel.kt              — MVVM: граф state, live reports, route result
  ui/ReportObstacleDialog.kt      — форма за пријава на пречка
  MainActivity.kt                 — OSMDroid мапа, тапнување за А/Б, цртање маркери/рута
```

## Пред прв build

1. **Firebase**: креирај нов Firebase проект (или искористи го истиот како
   ParkMK) и стави `google-services.json` во `app/`. Активирај Firestore.
2. **Firestore rules**: за MVP, дозволи read за сите, write само за
   автентицирани корисници:
   ```
   match /reports/{reportId} {
     allow read: if true;
     allow create: if request.auth != null;
     allow update: if request.auth != null; // за verify()
   }
   ```
3. **Auth**: моментално `authorId = "anon"` во MainActivity — вклучи
   FirebaseAuth (anonymous или Google sign-in, како во ParkMK) и замени го
   тоа со вистински uid.
4. **Overpass rate limit**: јавниот `overpass-api.de` сервер е добар за
   демо/тестирање, но не за производство. За реален lansирање, self-host-увај
   Overpass или предпроцесирај Bitola `.pbf` extract во bundled база при build.

## Што недостасува за production (следни чекори)

- Кеширање на графот локално (Room) за да работи routing и offline, наместо
  секој пат да се повлекува од Overpass.
- Фотографии за пријавите (моментално `photoUrl` полето постои во моделот,
  но upload UI сеуште не е имплементиран — додади Firebase Storage).
- Гејмификацијата од B2C слајдот (нивоа, поени, ranking) — `verificationCount`
  веќе постои како основа, треба само UI за leaderboard.
- Text-to-speech / TalkBack проверка за слепи корисници — моменталната UI
  користи стандардни Android widgets кои се пристапни, но треба реално
  тестирање со screen reader.
