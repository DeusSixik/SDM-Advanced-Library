# Сменяемые хранилища

Полный компилируемый walkthrough с командами запуска:
[REPOSITORY-EXAMPLE.md](REPOSITORY-EXAMPLE.md).

Приложение работает с `dev.sixik.sdmal.api.repository.Repository<K,V>`.
Меняются только фабрика `RepositoryManager` и настройки подключения:

```java
record ShopRecord(UUID id, String catalogId, int order) {}

var definition = new RepositoryDefinition<UUID, ShopRecord>(
        UUID::toString, UUID::fromString, ShopRecord::id,
        RepositoryCodec.gson(ShopRecord.class));

try (var manager = Repositories.json(Path.of("data/mymod"));
     var shops = manager.open("shops", definition)) {
    UUID id = UUID.randomUUID();
    shops.upsert(id, new ShopRecord(id, "building", 10));
    Optional<ShopRecord> value = shops.find(id);
    Map<UUID, ShopRecord> all = shops.findAll();
    shops.delete(id);
}
```

## Backend

| Фабрика | Хранение и зависимости |
| --- | --- |
| `Repositories.json(root)` | По JSON-файлу на запись, атомарная замена через временный файл |
| `Repositories.nbt(root)` | По сжатому NBT-файлу на запись |
| `Repositories.h2("jdbc:h2:file:./data/mymod")` | SQL без внешних зависимостей: H2 включена в мод |
| `Repositories.jdbc(url)` | JDBC; драйвер выбранной СУБД предоставляет приложение |
| `Repositories.jdbc(dataSource)` | Внешний пул/источник подключений; закрывается только заимствованное подключение |
| `Repositories.mongo(uri, database)` | MongoDB на официальном драйвере, включённом в мод |

H2 и MongoDB-драйверы изолированы в `dev.sixik.sdmal.internal.driver` и включены
в каждый загрузчик. Публичный API не раскрывает эти классы. SQL backend использует
таблицу `sdmal_repository_entries`: имя репозитория, строковый ключ, JSON payload.
Это документное CRUD-хранилище, не ORM: фильтры по отдельным полям, индексы payload,
связи и бизнес-транзакции приложение проектирует отдельно. JDBC-подключение к
MySQL/PostgreSQL/SQLite требует их драйверов и настроек; интеграционные проверки
этого изменения выполнялись на H2. У MySQL необходимо выбрать бинарную collation
ключевых колонок, если идентификаторы различаются регистром.

Ключи должны иметь взаимно обратимое, однозначное строковое представление,
не длиннее 512 символов. Имена репозиториев: буквы ASCII, цифры, `_`, `-`, `.`;
для одинаковой совместимости всех backend используйте до 120 символов.
Ключ entity проверяется против ключа операции. Для файлов имя вычисляется как
SHA-256 ключа: ключи вида `mod:path` и `../...` не становятся путями.
Размер файловой записи ограничен 16 MiB. Ошибки чтения, сериализации и записи
выбрасываются вызывающему коду; повреждённые записи не пропускаются молча.

## Частичные обновления SQL и MongoDB

Для обновления отдельных полей откройте `PatchableRepository` явно:

```java
var shops = manager.openPatchable("shops", definition);
shops.upsert(id, new ShopRecord(id, "building", 10));

var loaded = shops.findVersioned(id).orElseThrow();
var updated = shops.patch(id, loaded.revision(), patch -> patch
        .set("order", 20)
        .set("catalogId", "building_renamed"));
```

Каждая запись имеет непрозрачную UUID-ревизию. Если после чтения кто-то изменил
запись, `patch` выбрасывает `RepositoryConflictException`; ни одно поле такого
patch не применяется. Удаление и повторное создание записи тоже отменяет старый
токен. Даже `upsert` идентичного значения создаёт новую ревизию. Обычные `upsert`
и `delete` остаются безусловными: если нужна защита от потерянных правок, все
конкурирующие редакторы должны использовать `patch` с актуальным токеном.
При конфликте перечитайте данные и решите, имеет ли изменение смысл; автоматическое
повторение с новой ревизией может затереть чужую правку. Callback выполняется один
раз на вызывающем потоке, встроенного retry нет.

Поддерживаются:

- `set("price", 100)` — JSON-сериализация значения через Gson библиотеки;
- `set("description", null)` — явный JSON null;
- `setJson("details", "{\"enabled\":true}")` — готовый строгий JSON, полезный для своего codec;
- `unset("description")` — удалить поле, а не записать null.

Имя — свойство JSON, возвращённого codec, например `catalog_id` при таком naming.
Допускаются `[A-Za-z_][A-Za-z0-9_]{0,127}`. Это поля верхнего уровня: вложенный
объект или массив заменяется целиком. Путь `details.price`, Mongo-операторы,
пустой patch и изменение извлекаемого ключа отклоняются. Если поле указано несколько
раз, действует последняя операция. Кандидат проходит `decode` и проверку ключа до
записи. Остальные исходные поля сохраняются, даже если codec их не использует.

| Backend | Что отправляется при записи |
| --- | --- |
| MongoDB | Один атомарный `updateOne` с фильтром `_id + revision`, `$set`/`$unset` выбранных полей и новой ревизией |
| SQL, включая H2 | В транзакции меняется ревизия через compare-and-set, удаляются/вставляются только строки затронутых полей |

Для SQL используются отдельные `sdmal_repository_documents` и
`sdmal_repository_fields`; не нужны специфичные `JSON_SET`/`jsonb_set`.
Mongo хранит документы в `sdmal_fields_<name>` — этот префикс зарезервирован для
field storage, не открывайте такие физические коллекции через обычный `open`.
Значения полей в обоих backend — JSON-фрагменты в строках: большие числа, null и
пользовательские вложенные форматы не теряют точность из-за BSON-преобразования.
Это API частичной записи, а не ORM или поиск по BSON-полям. Для JDBC проверена H2;
другие СУБД требуют своего драйвера и транзакционного движка. У MySQL используйте
бинарную collation также для `field_name`, если регистр имён значим.

Перед записью `patch` пока **читает полную запись**, чтобы проверить результат
codec и неизменность ключа. Уменьшается исходящий payload обновления; объём чтения
и уведомления кеша остаются на уровне одной целой записи. Цена SQL-режима — больше
строк и сборка JSON при чтении, поэтому включайте его, когда частичные записи нужны.
Изменения полей не отслеживаются автоматически: вызывающий код задаёт patch явно.

`open` и `openPatchable` с одинаковым логическим именем используют разные форматы
и пространства хранения. Старые данные автоматически не перезаписываются:

```java
var source = manager.open("shops", definition);
var target = manager.openPatchable("shops", definition);
Repositories.copy(source, target);
```

Остановите записи на время миграции, проверьте результат и переключите приложение
на `openPatchable`. Повторный copy заменяет записи назначения полностью. JSON/NBT
файловые managers явно отклоняют `openPatchable`, без fallback на полную перезапись.

Нужен codec с JSON-объектом в корне. `RepositoryCodec.gson(Dto.class)` подходит;
opaque `RepositoryCodec.nbt()` / `component()` с base64 не подходят. Для частичных
обновлений ECS снимайте нужные поля компонента в DTO на owner-потоке либо напишите
JSON-object codec. Прямая полная запись компонентов через `open` остаётся доступной.
Не передавайте живой компонент в Gson.

Mongo field storage поддерживает те же подписки и `RepositoryStorage(..., true)`;
для них нужен replica set. Прямой patch не обновляет обычный неподписанный кеш:
явно перегрузите его либо включите наблюдение Mongo. Ревизии берите из
`findVersioned`, а не из кеша, который хранит только значения.

Компилируемый пример: [PartialUpdateRepositoryExample.java](src/main/java/dev/sixik/sdmal/example/repository/PartialUpdateRepositoryExample.java).
Запуск без внешнего сервера: `./gradlew.bat :core:1.20.1:repositoryPatchExample`.

## NBT, ECS и пользовательские типы

Отдельный компонент можно сохранять напрямую, без DTO и собственного codec:

```java
var definition = new RepositoryDefinition<String, CatalogComponent>(
        key -> key, key -> key, CatalogComponent::catalogId,
        RepositoryCodec.component(CatalogComponent.class));

try (var manager = Repositories.json(root);
     var catalogs = manager.open("catalogs", definition)) {
    catalogs.upsert("building", new CatalogComponent("building", 10));
    CatalogComponent loaded = catalogs.find("building").orElseThrow();
    // loaded отсоединён; при необходимости прикрепите его явно на owner-потоке.
}
```

`RepositoryCodec.component` использует существующие `Component.writeNbt/readNbt`,
записывает id типа, `dataVersion()` и payload. Если сохранённая версия старее текущей,
при чтении вызывается `migrateNbt`; будущая версия, неверный тип и неизвестный формат
отклоняются. Обычный `Component` сохраняет `@EcsPersist`; `@EcsSync`-only поля
остаются сетевыми. У `NetworkComponent` сохраняются его `field/tracked` по обычному
NBT-контракту. Изменения кодеков пользовательских полей также должны быть зарегистрированы
перед использованием типа, как для обычного ECS-архива.

Codec можно объявить до common setup: реестр запрашивается при чтении/записи.
К моменту использования тип должен быть обнаружен через `@EcsComponent` и
annotation processor либо зарегистрирован вручную. Декодер создаёт отсоединённый
компонент через реестр; owner, attach callbacks, UUID объекта и расписание тиков
не восстанавливаются. Ключ репозитория нужно извлекать из сохранённых полей компонента,
а не из `component.owner()`. В этом примере ключом служит `catalogId`; изменение
этого поля меняет ключ записи.

Для фоновой записи живого компонента сначала получите отсоединённый снимок на
его owner-потоке: `codec.readNbt(codec.writeNbt(live))`, затем передайте снимок в IO
и не меняйте его до завершения записи. Компилируемый вариант:
[ComponentRepositoryExample.java](src/main/java/dev/sixik/sdmal/example/repository/ComponentRepositoryExample.java).
Codec пишет нативный NBT в файловый NBT backend и lossless base64 payload в JSON,
SQL и MongoDB. Восстанавливается состояние конкретного класса; для сохранения
полного состава объектов, порядка компонентов и неизвестных записей используйте
архив менеджера.

`RepositoryCodec<V>` задаёт `encode/decode` JSON-представления независимо от backend.
Есть Gson-фабрики для класса и `TypeToken`; пользовательский codec может содержать
версию документа и миграцию. Для ECS-компонентов используйте `component` вместо Gson,
а для отдельных DTO снимайте необходимые данные на owner-потоке.

Для полного ECS-архива можно хранить один detached `CompoundTag`:

```java
var definition = new RepositoryDefinition<String, CompoundTag>(
        s -> s, s -> s, archive -> "main", RepositoryCodec.nbt());
var repository = manager.open("ecs_archive", definition);

// На owner-потоке EcsObjectManager:
CompoundTag snapshot = ecsManager.writeNbt();

// IO допускается на отдельном executor; живой менеджер там не используется.
repository.upsert("main", snapshot);

// Восстановление — снова на owner-потоке, только в свежий пустой менеджер:
repository.find("main").ifPresent(freshManager::readNbt);
```

Здесь `"main"` — постоянный ключ единственного архива. Для хранения нескольких
архивов определите DTO с собственным id и codec. `RepositoryCodec.nbt()` сохраняет
все NBT-типы, включая большие long и примитивные массивы. В NBT backend compound
пишется нативно; в JSON/SQL/Mongo используется lossless base64-представление.
`NbtRepositoryCodec<V>` позволяет написать такой codec для своего типа.
Обычный Gson codec в NBT backend хранит JSON payload в строковом теге.

## Кеш и потоки

`RepositoryStorage<K,V>` — отдельный read-through кеш:

```java
try (var cache = new RepositoryStorage<>(manager.open("shops", definition))) {
    cache.save(id, record);  // Сначала backend, затем кеш.
    cache.load(id);          // Кеш или чтение backend.
    cache.get(id);           // Только кеш, без IO.
    cache.loadAll();         // Заменяет кеш только после успешного чтения всех записей.
    cache.unload(id);        // Только удаление из кеша.
}
```

Все операции repository и cache синхронные; не выполняйте файловое/сетевое IO
в тике мира. Для фоновой работы используйте executor приложения и detached
снимки/DTO. После успешного future применяйте результат на серверном потоке.
Кеш возвращает те же экземпляры значений, поэтому используйте неизменяемые DTO
либо свою синхронизацию. Копии карты не означают глубокого копирования значений.
Закрытие cache закрывает подписку и его repository handle. Manager должен жить
дольше его handles; он закрывает принадлежащий ему MongoClient.

## Изменения MongoDB между серверами

CRUD работает и на standalone MongoDB. Подписки требуют replica set или sharded cluster.
Наблюдение включается явно:

```java
var repository = mongoManager.open("shops", definition);
var cache = new RepositoryStorage<>(repository, true);
cache.loadAll();
```

Открытие stream синхронное и может завершиться ошибкой. Cache автоматически
перечитывает последний вариант изменённого ключа при insert/update/delete,
включая собственные изменения. Callback выполняется на backend worker, поэтому
там нельзя обращаться к live ECS/Level. После терминальной ошибки stream
`cache.syncFailure()` содержит причину: заново откройте cache/подписку и перечитайте
данные. Драйвер автоматически возобновляет resumable streams; библиотека не
скрывает invalidation коллекции или ошибку callback бесконечным retry.

Для своих реакций: приведите handle к `ChangeAwareRepository<K,V>` и вызовите
`subscribe(listener, onFailure)`. Закройте возвращённый `RepositorySubscription`.
Это уведомления об изменениях, а не распределённые транзакции или очередь событий
с гарантией exactly-once.

## Перенос и расширение

```java
try (var source = Repositories.json(sourcePath);
     var target = Repositories.h2("jdbc:h2:file:./data/new_backend")) {
    var oldRepository = source.open("shops", definition);
    var newRepository = target.open("shops", definition);
    int copied = Repositories.copy(oldRepository, newRepository);
    // Проверить результат; затем переключить конфигурацию приложения.
}
```

Copy сохраняет исходник, делает upsert в назначение и не удаляет лишние записи.
Вся операция не является одной межбазовой транзакцией: при ошибке возможна частичная
копия. Остановите изменения приложения на время миграции. Старый формат SDMShop
не перезаписывается автоматически: импортируйте его отдельным codec/адаптером.

Собственный backend реализует `RepositoryManager` и `Repository`. Для выбора по
настройке создайте `RepositoryManagerRegistry` и зарегистрируйте фабрики:
`registry.register("json", () -> Repositories.json(root))`. Нет глобальных
singleton-соединений, скрытого fallback или привязки к MinecraftServer.

## Проверки

`./gradlew.bat buildAll` запускает обычные JUnit на пяти версиях и собирает
14 вариантов загрузчиков. Mongo integration test запускается только с явно
указанным тестовым сервером, создает уникальные базы и удаляет только их:

```powershell
$env:ORG_GRADLE_PROJECT_repositoryMongoUri = 'mongodb://127.0.0.1:27017/?replicaSet=rs0'
.\gradlew.bat :core:1.20.1:test --tests dev.sixik.sdmal.core.repository.MongoRepositoryIntegrationTest
Remove-Item Env:ORG_GRADLE_PROJECT_repositoryMongoUri
```

Для нового одноузлового тестового `mongod --replSet ...` дополнительно существует
`-PrepositoryMongoInit=true`: он инициализирует replica set. Не используйте его
против рабочих баз.
