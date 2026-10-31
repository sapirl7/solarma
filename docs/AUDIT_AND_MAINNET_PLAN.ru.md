# Solarma: аудит проекта и план запуска на mainnet

Дата: 2026-10-31. Ревизия: `main` @ `99bd3f5`.

Документ состоит из трёх частей: в каком состоянии проект, что означает потеря ключа,
и пошаговый план перезапуска на Solana mainnet-beta с новыми ключами.

---

## 1. Короткий итог

- **Контракт в хорошем состоянии.** Собирается, все 156 Rust-тестов (unit и property)
  проходят локально. В CI проходят `anchor build` и 70 интеграционных тестов (TS) на localnet.
  Критических уязвимостей, позволяющих украсть чужие средства, не найдено.
- **Android-клиент на `main` был сломан.** Из-за неверного ID программы ComputeBudget ни одна
  транзакция не сериализовалась корректно (A-1, исправлено в этой ветке). CI это не ловил,
  потому что тест проверял ту же ошибку. Кроме того, у клиента есть системные проблемы
  с возвратом депозита (A-2, A-3), и их нужно решить до mainnet.
- CI зелёный, кроме шага `npm audit` (см. §5).
- **Сейчас проект работает только в devnet.** Программа `F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP`,
  последний релиз приложения v0.2.4 (2026-02-10). Последний коммит в `main` от 2026-06-25.
  С тех пор висят ~20 PR от dependabot и 4 твоих PR (#77, #78, #79, #80).
- **Потеря ключа не мешает запуску на mainnet.** Для mainnet всё равно нужны новый
  Program ID и новый upgrade authority. Код не привязан к старому ключу, кроме
  захардкоженного Program ID в трёх местах.
- **Блокеры для mainnet с реальными деньгами** (подробно в §3 и §4):
  1. В Android один Program ID на все сети, а в настройках есть переключатель Mainnet.
     Это нужно исправить до любого релиза (A-4).
  2. Возврат депозита не гарантирован: ACK и CLAIM только ставятся в очередь до следующего
     открытия приложения, `sweep_acknowledged` не реализован, локальные дедлайны расходятся
     с on-chain (A-2, A-3, A-7).
  3. Маршрут «Donate» отправляет штрафы на кошелёк `2g536HdC3ByKNZc6gev6jb6NmFr7dMnTnp66Gqk5rusk`
     (по истории коммитов это «publisher wallet»), а не на благотворительность, как написано в README.
     Если утерян именно этот ключ, все «пожертвования» уйдут на недоступный адрес.
  4. PR #78 (ограничение дедлайна) ещё не влит. Без него возможна вечная блокировка средств.
  5. Нет внешнего аудита. Upgrade authority для mainnet ещё не выбран (multisig или Ledger).

---

## 2. Потеря ключа: что это значит

В проекте несколько разных ключей, и последствия потери у каждого свои.

| Ключ | Где обычно лежит | Что теряется | Что делать |
|------|------------------|--------------|------------|
| **Program keypair** (`solarma_vault-keypair.json`) | `programs/target/deploy/` | Возможность задеплоить программу по адресу `F54Lp…` в **другой** сети, например в mainnet. На devnet она уже задеплоена, этот ключ там больше не нужен. | Сгенерировать новый keypair, получится новый Program ID. Нормальная практика для mainnet. |
| **Upgrade authority** (обычно `~/.config/solana/id.json`) | локально | Devnet-программу `F54Lp…` нельзя обновить или закрыть. Она продолжит работать как есть. | На devnet ничего не делать: devnet SOL ничего не стоит. Для mainnet завести новый authority, лучше Squads multisig или Ledger. |
| **Android keystore** (`.jks`) | локально, возможно в секрете CI `KEYSTORE_BASE64` | Нельзя выпустить обновление того же приложения с той же подписью: на устройстве не обновится поверх, в магазине тоже. | Сначала проверь *Settings → Secrets and variables → Actions*: `release-build.yml` читает секрет `KEYSTORE_BASE64`. Если он там есть, ключ можно восстановить своим разовым workflow. Если нет, нужен новый keystore и, скорее всего, новая публикация в Solana dApp Store или Google Play. |
| **Publisher / treasury wallet** `2g536…rusk` | кошелёк | Доступ к средствам, пришедшим по маршруту Donate. Если через этот кошелёк публиковали в dApp Store, то и управление листингом. | В коде заменить адрес (§4). По листингу писать в поддержку Solana Mobile. |

### Что проверить в первую очередь (выполнить локально)

```bash
# 1. Состояние devnet-программы: кто authority, совпадает ли с каким-то из твоих ключей
solana program show F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP -u devnet

# 2. ВАЖНО: нет ли аккаунта по этому адресу в mainnet.
#    Ожидаемый ответ: "AccountNotFound". Если аккаунт есть и это не ты, ключ утёк (см. ниже).
solana account F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP -u mainnet-beta

# 3. Какие ключи у тебя остались
solana-keygen pubkey ~/.config/solana/id.json
ls -la programs/target/deploy/ programs/solarma_vault/target/deploy/ 2>/dev/null
```

**Почему важен пункт 2.** В выпущенном APK v0.2.4 можно переключиться на Mainnet, а Program ID
при этом остаётся `F54Lp…`. Если ключ программы не потерян, а **украден**, злоумышленник может
задеплоить по этому адресу в mainnet свою программу. Транзакция `create_alarm` подписана
пользователем, поэтому такая программа сможет вывести через CPI весь SOL с его кошелька.
Пока по адресу в mainnet ничего нет, риск теоретический. Но новый APK должен выйти с
исправленным переключателем сети до того, как кто-то начнёт пользоваться mainnet.

---

## 3. Аудит контракта (`programs/solarma_vault`)

### Что сделано хорошо

- Вся арифметика через `checked_*`, `overflow-checks = true` в release-профиле.
- Все PDA проверяются по seeds и bump. Владельца проверяет `has_one = owner`.
  Статус alarm проверяется в constraint каждой инструкции.
- Окна claim, slash, snooze и refund не пересекаются, это покрыто property-тестами.
- Есть защита от падения vault ниже rent-exempt (snooze и emergency_refund).
- Snooze идемпотентен (`expected_snooze_count`).
- Адрес incinerator в `BURN_SINK` проверен: байты декодируются в `1nc1nerator11111111111111111111111111111111`.

### Находки

| # | Важность | Находка | Где | Рекомендация |
|---|----------|---------|-----|--------------|
| P-1 | **High** (продукт, не кража) | `ack_awake` проверяет только подпись владельца, сама «проверка пробуждения» происходит на клиенте. Любой, у кого есть свой ключ, может вызвать `ack_awake` скриптом, не вставая. Деньги третьих лиц при этом не страдают: пользователь обходит только своё обязательство. Но buddy и donate теряют смысл. | `instructions/ack_awake.rs` | Решить до mainnet: запуститься с честно описанной моделью «self-commitment» или влить PR #79 (commit/reveal), обновив клиент синхронно. |
| P-2 | **Medium** | Нет верхней границы `deadline`. Если `deadline ≈ i64::MAX`, то `deadline + CLAIM_GRACE_SECONDS` переполняется: claim и sweep падают с `Overflow`, а slash никогда не наступает. Депозит заблокирован навсегда (пользователь вредит только себе). | `create_alarm.rs`, `helpers.rs` | **Влить PR #78** (горизонт 30 дней). |
| P-3 | **Medium** (экономика) | Аккаунт `Alarm` (182 байта, ≈0.00216 SOL ренты) не закрывается ни в одном терминальном состоянии. Рента теряется навсегда. При депозите 0.01 SOL это ~22% скрытой стоимости на каждый будильник. | `claim.rs`, `slash.rs`, `sweep_acknowledged.rs`, `emergency_refund.rs` | Добавить `close = owner` для alarm в терминальных переходах или отдельную инструкцию `close_alarm`. Это меняет интерфейс, и импорт истории будет опираться на события, а не на аккаунты. |
| P-4 | **Low** | `penalty_destination` никак не валидируется. Если он равен владельцу, buddy или donate «наказывает» сам себя. Если равен PDA vault, `slash` никогда не пройдёт: Anchor `close` в тот же аккаунт обнуляет лампорты, и runtime отклоняет транзакцию. Депозит заморожен навсегда. Проверено по исходнику `anchor-lang 0.32.1/src/common.rs`. | `create_alarm.rs` | `require!(dest != owner && dest != alarm && dest != vault)`. Для Donate на mainnet лучше whitelist адресов прямо в программе. |
| P-5 | Low | Нет глобальной паузы или конфига. Единственный аварийный рычаг — upgrade authority. | `lib.rs` | Нормально, если authority — multisig. Иначе стоит добавить `Config` PDA с флагом `paused` для `create_alarm`. |
| P-6 | Info | `initialize` / `UserProfile` не используется клиентом, `tag_hash` нигде не пишется. | `initialize.rs` | Удалить или оставить. Не мешает. |
| P-7 | Info | `sweep_acknowledged` эмитит `AlarmClaimed`, поэтому индексер не отличит sweep от claim. `AlarmSlashed.slashed_amount` не включает ренту vault. | `events.rs` | Косметика для индексеров. |
| P-8 | Info | Нет `security_txt!`, verifiable build и публикации IDL в процессе деплоя. | — | Добавить перед mainnet (§6, фаза 4). |

### Скрипты деплоя

- `scripts/deploy-devnet.sh` использует `sed -i ''` (синтаксис macOS). На Linux команда падает.
- Если keypair нет, скрипт молча вызывает `anchor build`, который **генерирует новый случайный
  Program ID**, и переписывает `declare_id!`. Для mainnet такое недопустимо: ключ программы
  должен создаваться осознанно и сразу попадать в бэкап.
- В `Anchor.toml` нет секции `[programs.mainnet]`.

---

## 4. Аудит Android-клиента

### Что сверено и совпадает с контрактом

Совпадения проверены вычислением:
- все 6 discriminators инструкций (`sha256("global:<name>")[0..8]`);
- дискриминатор аккаунта `Alarm`;
- адрес incinerator.

Совпадения проверены чтением кода:
- порядок и типы аргументов, little-endian;
- порядок аккаунтов и флаги signer/writable;
- seeds PDA;
- смещения в `OnchainAlarmParser`, включая `Option<Pubkey>`.

Приватных ключей и секретов в репозитории нет, по истории git тоже.

### Находки

| # | Важность | Находка | Где | Рекомендация |
|---|----------|---------|-----|--------------|
| A-1 | **Critical**, ✅ исправлено в этой ветке | ID программы ComputeBudget был записан строкой из 37 символов (`ComputeBudget111…1`) вместо 43. sol4k не проверяет длину и декодировал её в **27 байт**. Каждая транзакция содержит compute-budget инструкции, значит каждое сериализованное сообщение было битым. Регрессия из `a413721` (2026-02-15), то есть после релиза v0.2.4: выпущенный APK не затронут, но **любая сборка из `main` не могла отправить ни одной транзакции**. Тест проверял ту же неверную строку. | `wallet/TransactionBuilder.kt:30`, `ComputeBudgetTest.kt` | Исправлена строка, добавлен тест «ключ = 32 байта». Остальные захардкоженные ключи проверены, все по 32 байта. |
| A-2 | **Critical** | Возврат депозита зависит от того, откроет ли пользователь главное приложение вовремя. После wake proof `AlarmActivity` только **ставит в очередь** ACK и CLAIM. Очередь обрабатывается в `MainActivity.onResume`. CLAIM отправляется сразу после ACK, без ожидания подтверждения. В клиенте **нет `sweep_acknowledged`**: если ACK прошёл, а CLAIM опоздал на `deadline + 120 с`, средства зависают. Импорт с блокчейна пропускает alarm со статусом ≠ Created. | `alarm/AlarmActivity.kt:127-133`, `MainActivity.kt:192`, `AlarmRepository.kt:376` | Отправлять `ack_awake` и `claim` **одной транзакцией** прямо из `AlarmActivity`. Реализовать `sweep_acknowledged`. Импортировать alarm в статусе Acknowledged. |
| A-3 | **Critical** | Локальное время расходится с on-chain. Snooze локально переносит звонок на `now + 5 мин`, а on-chain `alarm_time` и `deadline` сдвигаются на `+300 с` от **старого** значения, причём только если транзакция snooze реально прошла (она тоже только в очереди). Все проверки дедлайна в клиенте считают его как «локальное время + 30 мин». Пользователя могут разбудить уже после реального дедлайна. | `AlarmRepository.kt:180-185`, `AlarmActivity.kt:317-347`, `TransactionProcessor.kt:155-180` | Хранить on-chain `alarm_time`/`deadline` и брать их из аккаунта после каждой транзакции. |
| A-4 | **High** | Переключатель Mainnet в настройках: Program ID при этом остаётся devnet-овским. Баннер «No real SOL at risk», бейдж «DEV TEST» и кнопка faucet не скрываются. Локальная БД общая для обеих сетей, так что devnet-будильники будут «клеймиться» в mainnet. Сеть применяется асинхронно, когда очередь уже запущена на devnet-настройках. Владелец передаётся в программу как **writable signer**, поэтому чужая программа по адресу `F54Lp…` в mainnet смогла бы опустошить кошелёк (см. §2). | `SettingsScreen.kt:160-172`, `HomeScreen.kt:75,324,384,402-413`, `MainActivity.kt:174-192`, `SolarmaInstructionBuilder.kt:22` | Сеть и Program ID задавать **build flavor** (`devnet`/`mainnet`), переключатель убрать, БД разделить. |
| A-5 | **High** | Депозиты-«сироты». Любая ошибка при создании (кроме pending), например таймаут всех RPC после того, как транзакция уже ушла, стирает `onchainPubkey`. Синхронизация потом найдёт PDA, но не запланирует будильник, потеряет `penaltyDestination` и запишет `initialAmount` вместо остатка. Депозит может быть тихо slash-нут. | `CreateAlarmViewModel.kt:259-269`, `AlarmRepository.kt:290-297`, `OnchainAlarmService.kt:440-453` | Считать результат неизвестным до истечения blockhash. Перед очисткой проверять, существует ли PDA. |
| A-6 | **High** | Подтверждение и ретраи. Статус проверяется один раз сразу после отправки, поэтому транзакция почти всегда переподписывается заново. `ErrorClassifier` ищет ошибки по *именам*, а RPC возвращает `custom program error: 0x…`: любая ошибка программы считается retryable. Двойного списания нет, его не допускают guards контракта, но локальное состояние становится неверным. | `TransactionProcessor.kt:231`, `ErrorClassifier.kt`, `SolanaRpcClient.kt:258,284` | Сохранять signature и `lastValidBlockHeight` и опрашивать до истечения. Маппить коды Anchor (6000+). `preflightCommitment: confirmed`. |
| A-7 | **High** | Перезагрузка телефона в окне grace: пропущенный будильник сразу помечается проваленным и ставится slash, хотя до on-chain дедлайна можно было успеть. | `AlarmRepository.kt:208-229` | Звонить, если ещё не наступил on-chain `deadline`. |
| A-8 | Medium | Slash-воркеры подписывают **кошельком самого владельца**: пользователь платит комиссию, чтобы сжечь свой депозит. Для Buddy не учитывается окно buddy-only (бесконечные ретраи). Для Donate берётся текущая константа `SolarmaTreasury.ADDRESS`, а не `penalty_destination` из аккаунта. | `TransactionBuilder.kt:266-280`, `SlashAlarmWorker.kt` | Не слэшить от имени владельца, slash permissionless. Брать destination из on-chain данных. |
| A-9 | Medium | Фиксированный priority fee (50k µL/CU × 200k CU = 10 000 lamports; в комментарии ошибочно написано «0.01 SOL»). Blockhash берётся **до** открытия кошелька: сонный пользователь, который подтверждает дольше 60–90 с, получит просроченную транзакцию. Результат `authorize` в MWA игнорируется. | `TransactionBuilder.kt:36,291`, `WalletManager.kt:174-177,214-216` | `getRecentPrioritizationFees` и симуляция CU. `signAndSendTransactions` из MWA 2.0. Проверять авторизованный аккаунт. |
| A-10 | Low | Если нет `keystore.properties`, release-сборка молча подписывается debug-ключом (то же в `release-build.yml` при ручном запуске). RPC URL с API-ключом зашивается в `BuildConfig`, то есть ключ публичен. Логи пишут hex транзакций и адреса, логи можно экспортировать из настроек. `BootReceiver` экспортирован и принимает незащищённые `QUICKBOOT`-интенты. В дефолтных RPC есть мёртвые и демо-эндпоинты. | `build.gradle.kts:61-69`, `proguard-rules.pro`, `AndroidManifest.xml:58-64`, `SolanaRpcClient.kt:29-43` | Падать без ключа подписи. Использовать RPC-ключ с ограничением по домену или приложению. Вырезать `Log.d` в release. Убрать QUICKBOOT. |

Находки A-2…A-10 найдены чтением кода. A-1 подтверждена вычислением (декодирование base58).
A-2 и A-3 дополнительно сверены вручную.

---

## 5. Инфраструктура и CI

- CI на `main`: `rust-checks`, `anchor`, `anchor-full`, `android`, `security`, `docs` зелёные.
  Ночной прогон краснеет только на `npm audit (production deps)`: уязвимости в
  `@coral-xyz/anchor`, `@solana/web3.js` и `toml`.
  - Причина: в `programs/solarma_vault/package.json` пакет `@coral-xyz/anchor` указан как
    `dependency`, хотя нужен только тестам. Если перенести его в `devDependencies`,
    шаг станет зелёным честно, ничего не скрывая: production-кода на JS здесь нет.
- `release-build.yml` для тегов v0.2.4, v1.3.0 и v1.3.1 падал (логи уже удалены GitHub).
- ~20 открытых PR от dependabot, в том числе мажорные (AGP 9, TypeScript 7, mocha 12).
  Перед mainnet лучше влить патчи и миноры, а мажорные обновления отложить.
- PR #77 (pin Actions по SHA) стоит влить: это защита цепочки поставок.

---

## 6. План восстановления и запуска на mainnet

### Фаза 0. Инвентаризация (1 день)

1. Выполнить проверки из §2 и записать, какие ключи живы, а какие потеряны.
2. Проверить секреты репозитория (`KEYSTORE_BASE64` и другие).
3. Заполнить `docs/UPGRADE_POLICY.md` → *Current State*.

### Фаза 1. Блокеры в коде

1. **Контракт:** влить PR #78. Решить по P-1 (#79 или нет), P-3 (закрытие alarm) и P-4
   (валидация destination). Любое изменение контракта до первого деплоя в mainnet обходится
   дёшево, после — только через upgrade.
2. **Android** (по порядку):
   - A-1 уже исправлено в этой ветке. Прогнать `./gradlew testDebugUnitTest` и CI.
   - A-4: build flavors `devnet`/`mainnet` с собственными Program ID, RPC и адресом Donate.
     Убрать переключатель сети и devnet-тексты из mainnet-сборки. Отдельная БД.
   - A-2, A-3, A-7: `ack_awake + claim` одной транзакцией из экрана будильника, `sweep_acknowledged`,
     дедлайны брать из on-chain аккаунта.
   - A-5, A-6: корректное подтверждение транзакций и маппинг ошибок Anchor.
   - Добавить snapshot-тест **полной** транзакции (с compute budget), чтобы A-1 не повторилась.
3. **CI:** перенести `@coral-xyz/anchor` в devDependencies, влить #77.

### Фаза 2. Новые ключи

```bash
# Program ID для mainnet: отдельный ключ, сразу в зашифрованный бэкап.
# Можно vanity: solana-keygen grind --starts-with sol:1
solana-keygen new -o ~/solarma-keys/solarma_vault-mainnet.json
solana-keygen pubkey ~/solarma-keys/solarma_vault-mainnet.json   # → NEW_PROGRAM_ID

# Deployer (горячий ключ, на нём только SOL на деплой)
solana-keygen new -o ~/solarma-keys/deployer-mainnet.json
```

- **Upgrade authority:** Squads multisig (рекомендуется, например 2 из 3 на разных устройствах)
  или Ledger. После деплоя authority сразу передаётся на multisig.
- Бэкап: seed-фразы на бумаге, в двух физически разных местах. Без облака (как в `docs/KEY_MANAGEMENT.md`).
- Новый Android keystore (если старый не восстановлен): пароль в менеджере паролей,
  зашифрованная копия на отдельном носителе.

### Фаза 3. Репетиция на devnet с новым ID

Devnet-ключ не восстановить, поэтому для devnet тоже нужен новый Program ID (отдельный от mainnet).

```bash
cd programs/solarma_vault
anchor keys sync          # синхронизирует declare_id! и Anchor.toml с target/deploy/*.json
anchor build --verifiable
anchor deploy --provider.cluster devnet
anchor test --skip-deploy --provider.cluster devnet   # или smoke-тесты вручную
```

Затем собрать APK с новым devnet ID и пройти `docs/QA_MATRIX.md` на реальном Seeker.

### Фаза 4. Деплой в mainnet

Бюджет: программа весит порядка 300–400 KB, рента programdata около 2–3 SOL, плюс временный
buffer на время деплоя (возвращается). Иметь на deployer **5–6 SOL**.

```bash
solana config set -u <твой_приватный_RPC_mainnet> -k ~/solarma-keys/deployer-mainnet.json
anchor build --verifiable
solana program deploy target/verifiable/solarma_vault.so \
  --program-id ~/solarma-keys/solarma_vault-mainnet.json \
  --with-compute-unit-price 50000 --max-sign-attempts 50
solana program set-upgrade-authority <NEW_PROGRAM_ID> \
  --new-upgrade-authority <SQUADS_VAULT_PUBKEY> --skip-new-upgrade-authority-signer-check
solana program show <NEW_PROGRAM_ID>        # проверить authority
# verified build для эксплореров:
solana-verify verify-from-repo -u mainnet-beta --program-id <NEW_PROGRAM_ID> \
  https://github.com/sapirl7/solarma --library-name solarma_vault --mount-path programs
```

Путь к `.so` зависит от раскладки workspace (`programs/target/…` или `programs/solarma_vault/target/…`):
бери тот, что напечатает `anchor build --verifiable`. Флаги CLI сверь с `solana --version`
(в документации указан Agave 2.3.0).

Перед этим добавить в программу `security_txt!` (крейт `solana-security-txt`) с контактом из `SECURITY.md`.

### Фаза 5. Релиз приложения

1. APK с mainnet Program ID и приватным RPC (Helius, Triton или QuickNode; ключ только в
   `~/.gradle/gradle.properties`).
2. Мягкий запуск: в UI ограничить максимальный депозит (например, 0.1 SOL) на первые недели.
3. Обновить README: убрать «Currently on Devnet», указать mainnet Program ID и модель доверия (P-1).

### Фаза 6. После запуска

- Мониторинг событий программы: `scripts/monitor_program_events.cjs`, или Helius webhooks.
- Внешний аудит до снятия лимита депозита.
- Через несколько месяцев стабильной работы решить, делать ли программу immutable.

---

## 7. Решения, которые нужны от владельца

1. Какой именно ключ утерян (§2): от этого зависит, нужен ли новый листинг приложения.
2. Upgrade authority для mainnet: Squads multisig или Ledger.
3. Wake proof: запускаться на модели «self-commitment» (P-1 как есть) или сначала довести PR #79.
4. Закрывать ли аккаунты `Alarm` (P-3). Меняет интерфейс и импорт истории.
5. Куда идёт «Donate» на mainnet: настоящий благотворительный адрес или казна проекта.
   В последнем случае переименовать маршрут, чтобы не вводить пользователей в заблуждение.
