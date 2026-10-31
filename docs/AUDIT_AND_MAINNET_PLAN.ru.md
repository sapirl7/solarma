# Solarma: аудит проекта и план запуска на mainnet

Дата: 2026-10-31. Ревизия: `main` @ `99bd3f5`.

Документ состоит из трёх частей: в каком состоянии проект, что означает потеря ключа,
и пошаговый план перезапуска на Solana mainnet-beta с новыми ключами.

---

## 1. Короткий итог

- **Код в рабочем состоянии.** Контракт собирается, все 156 Rust-тестов (unit и property)
  проходят локально. В CI проходят `anchor build`, 70 интеграционных тестов (TS) на localnet,
  а также ktlint, lint, unit-тесты и сборка APK для Android. Красный только шаг
  `npm audit` (см. §5).
- **Сейчас проект работает только в devnet.** Программа `F54LpWS97bCvkn5PGfUsFi8cU8HyYBZgyozkSkAbAjzP`,
  последний релиз приложения v0.2.4 (2026-02-10). Последний коммит в `main` от 2026-06-25.
  С тех пор висят ~20 PR от dependabot и 4 твоих PR (#77, #78, #79, #80).
- **Потеря ключа не мешает запуску на mainnet.** Для mainnet всё равно нужны новый
  Program ID и новый upgrade authority. Код не привязан к старому ключу, кроме
  захардкоженного Program ID в трёх местах.
- **Блокеры для mainnet с реальными деньгами** (подробно в §3 и §4):
  1. В Android один Program ID на все сети, а в настройках есть переключатель Mainnet.
     Это нужно исправить до любого релиза.
  2. Маршрут «Donate» отправляет штрафы на кошелёк `2g536HdC3ByKNZc6gev6jb6NmFr7dMnTnp66Gqk5rusk`
     (по истории коммитов это «publisher wallet»), а не на благотворительность, как написано в README.
     Если утерян именно этот ключ, все «пожертвования» уйдут на недоступный адрес.
  3. PR #78 (ограничение дедлайна) ещё не влит. Без него возможна вечная блокировка средств.
  4. Нет внешнего аудита. Upgrade authority для mainnet ещё не выбран (multisig или Ledger).

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

<!-- ANDROID_SECTION -->

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
2. **Android:** Program ID задавать по сети через `BuildConfig` или Gradle-свойства.
   Переключатель Mainnet скрыть, пока для mainnet не задан Program ID. Адрес Donate
   вынести в конфиг по сетям. Остальное из §4.
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
