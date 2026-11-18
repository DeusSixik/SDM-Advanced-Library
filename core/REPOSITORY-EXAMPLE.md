# Полный пример Repository API

Пример находится в `dev.sixik.sdmal.example.repository` и компилируется вместе
с библиотекой на всех поддерживаемых версиях Minecraft.

## Запустить локально

Из корня проекта:

```powershell
.\gradlew.bat :core:1.20.1:repositoryExample
```

Задача запускает `RepositoryUsageExample.main` на обычном Java-потоке, без игры
и внешнего сервера БД. Результат:

```text
Repository example completed: .../build/example/repository/run-...
Shop after cache save and reload: ShopRecord[id=..., catalogId=building_updated, order=21]
Records retained in JSON: 2
Verified copies: NBT and H2
```

Папка печатается в консоль. В ней остаются JSON-записи, сжатые NBT-файлы и H2-база.
Каждый запуск создаёт новую папку `run-...`, поэтому пример не стирает предыдущие
результаты. Выполнять этот блокирующий сценарий внутри тика Minecraft не следует.

| Файл | Что показывает |
| --- | --- |
| [ShopRepositoryExample.java](src/main/java/dev/sixik/sdmal/example/repository/ShopRepositoryExample.java) | DTO, общий `RepositoryDefinition`, снимок живого ECS-магазина |
| [RepositoryUsageExample.java](src/main/java/dev/sixik/sdmal/example/repository/RepositoryUsageExample.java) | Полный локальный сценарий с CRUD, кешем, registry, закрытием и переносом |
| [VersionedShopCodec.java](src/main/java/dev/sixik/sdmal/example/repository/VersionedShopCodec.java) | Свой codec и миграция старого JSON |
| [AsyncEcsRepositoryExample.java](src/main/java/dev/sixik/sdmal/example/repository/AsyncEcsRepositoryExample.java) | Снимок ECS на owner-потоке, IO в фоне, восстановление на owner-потоке |
| [ComponentRepositoryExample.java](src/main/java/dev/sixik/sdmal/example/repository/ComponentRepositoryExample.java) | Прямое сохранение компонента через `RepositoryCodec.component` и безопасная передача снимка в IO |
| [MongoRepositoryExample.java](src/main/java/dev/sixik/sdmal/example/repository/MongoRepositoryExample.java) | Два независимых подключения, change streams, кеш и явные подписки |
| [PartialUpdateRepositoryExample.java](src/main/java/dev/sixik/sdmal/example/repository/PartialUpdateRepositoryExample.java) | Частичная запись JSON-полей, ревизии, конфликт и явное перечитывание |

## 1. Описать данные один раз

В примере используется неизменяемый DTO:

```java
public record ShopRecord(UUID id, String catalogId, int order) {}

public static final RepositoryDefinition<UUID, ShopRecord> DEFINITION =
        new RepositoryDefinition<>(
                UUID::toString,
                UUID::fromString,
                ShopRecord::id,
                RepositoryCodec.gson(ShopRecord.class));
```

Первые два аргумента преобразуют ключ в стабильную строку и обратно. Третий
извлекает ключ из значения. Последний отвечает за формат payload. Эту definition
можно использовать в JSON, NBT, H2, другом JDBC backend или MongoDB.

Живой `EcsObject` не передаётся в Gson. Метод `ShopRepositoryExample.snapshot(shop)`
читает `CatalogComponent` на owner-потоке и возвращает отдельный `ShopRecord`.

## 2. CRUD и повторное открытие

Точная реализация — `RepositoryUsageExample.demonstrateCrud`. Минимальный вариант
с настоящими типами примера:

```java
import dev.sixik.sdmal.api.repository.Repositories;
import dev.sixik.sdmal.example.repository.ShopRepositoryExample;
import dev.sixik.sdmal.example.repository.ShopRepositoryExample.ShopRecord;
import java.nio.file.Path;
import java.util.UUID;

try (var manager = Repositories.json(Path.of("data/mymod"));
     var shops = manager.open("shops", ShopRepositoryExample.DEFINITION)) {
    UUID id = UUID.randomUUID();
    shops.upsert(id, new ShopRecord(id, "building", 10));

    ShopRecord loaded = shops.find(id).orElseThrow();
    shops.upsert(id, new ShopRecord(id, "building_renamed", loaded.order()));

    var all = shops.findAll();
    boolean deleted = shops.delete(id);
}
```

`find` возвращает `Optional.empty()` только для отсутствующей записи. Ошибки
backend или десериализации пробрасываются вызывающему коду. В executable-примере
также показаны `upsertAll`, `deleteAll`, закрытие и повторное открытие manager:
файлы сохраняются, а handles после закрытия не используются.

## 3. Выбрать backend по настройке

`RepositoryUsageExample.backends` использует локальный реестр фабрик:

```java
var backends = new RepositoryManagerRegistry();
backends.register("json", () -> Repositories.json(root.resolve("json")));
backends.register("nbt", () -> Repositories.nbt(root.resolve("nbt")));
backends.register("h2", () -> Repositories.h2("jdbc:h2:file:" + root.resolve("sql/shops")));

try (var manager = backends.create("h2")) {
    var shops = manager.open("shops", ShopRepositoryExample.DEFINITION);
}
```

Вместо H2 можно передать `Repositories.jdbc(dataSource)` или
`Repositories.jdbc(jdbcUrl)`; соответствующий внешний JDBC-драйвер обеспечивает
приложение. H2 уже включена в библиотеку. Mongo выбирается через
`Repositories.mongo(uri, database)`.

## 4. Использовать кеш осознанно

В локальном walkthrough кеш открывается после закрытия первоначального manager,
так что данные действительно перечитываются с диска:

1. `loadAll()` загружает записи в кеш.
2. `get(id)` читает только память.
3. `save(id, value)` сначала сохраняет backend и только после успеха меняет кеш.
4. `unload(id)` удаляет только локальную запись; `load(id)` снова читает backend.
5. `putLocal(id, value)` создаёт несохранённый preview.
6. `loadAll()` заменяет preview сохранёнными данными.
7. `delete(id)` удаляет запись и из backend, и из кеша.

Кеш владеет переданным ему repository handle. Закрывайте кеш до manager:

```java
try (var manager = Repositories.json(root);
     var cache = new RepositoryStorage<>(
             manager.open("shops", ShopRepositoryExample.DEFINITION))) {
    cache.loadAll();
}
```

Обычный кеш не следит за внешними изменениями. Для Mongo наблюдение включается
вторым аргументом `true`. Значения возвращаются без глубокого копирования;
поэтому здесь используются immutable records.

## 5. Перенести существующие записи

Одного выбора другого manager недостаточно для переноса старых данных.
В `runLocal` миграция выполняется явно:

```java
var expected = source.findAll();
int copied = Repositories.copy(source, nbt);
Repositories.copy(nbt, sql);

if (!expected.equals(sql.findAll())) {
    throw new IllegalStateException("Migration verification failed");
}
```

Source остаётся нетронутым. Пример проверяет одинаковые записи в JSON, NBT и H2,
а тест повторно открывает все три хранилища после закрытия исходных handles.
Во время реальной миграции остановите записи приложения; межбазовой транзакции
здесь нет. Переключайте настройку только после проверки назначения.

## 6. Добавить свой codec и миграцию формата

`VersionedShopCodec` пишет поля `version`, `id`, `catalog_id`, `order`.
При чтении он принимает старую версию без `version`, в которой каталог назывался
`catalog`, и возвращает текущий `ShopRecord`. Будущая неизвестная версия отклоняется.

Локальный пример открывает отдельный репозиторий `versioned_shops` с
`VersionedShopCodec.DEFINITION` и проверяет round-trip. Смена definition уже
заполненного репозитория требует codec, который умеет читать прежний формат;
поэтому пример не перезаписывает Gson-коллекцию другим codec.

## 7. Сохранять ECS асинхронно

Если нужен сам компонент вместо DTO, используйте
`ComponentRepositoryExample.DEFINITION`: его factory —
`RepositoryCodec.component(CatalogComponent.class)`.

```java
var catalogs = backend.open("catalogs", ComponentRepositoryExample.DEFINITION);
var saved = ComponentRepositoryExample.saveCatalog(shop, catalogs, ioExecutor);
```

Вызов выполняется на owner-потоке. Метод сначала получает независимую копию
компонента через его persistence-codec, затем пишет эту копию в IO. Загруженный
`CatalogComponent` не имеет owner и при необходимости прикрепляется явно.
В записи сохраняются id типа и версия схемы, а чтение старых данных использует
`migrateNbt`. Тип должен быть зарегистрирован при setup; поле ключа должно
сохраняться через `@EcsPersist`. Поля только с `@EcsSync` в repository не уходят.

`AsyncEcsRepositoryExample.saveShop` снимает DTO до запуска IO:

```java
ShopRecord snapshot = ShopRepositoryExample.snapshot(shop);
return CompletableFuture.runAsync(
        () -> repository.upsert(snapshot.id(), snapshot), ioExecutor);
```

Для полного менеджера есть `saveArchive` и `restoreArchive`.
Архив хранится через `RepositoryCodec.nbt()`; неизвестные NBT-типы и большие long
не преобразуются в обычные JSON-числа.

После discovery компонентов, на owner-потоке:

```java
var source = new EcsObjectManager();
var restored = new EcsObjectManager();
var shop = ShopEcsExample.createShop(source, "building", 10);

var repository = backend.open("ecs_archive", AsyncEcsRepositoryExample.ARCHIVES);
var completed = AsyncEcsRepositoryExample.saveArchive(source, repository, ioExecutor)
        .thenCompose(ignored -> AsyncEcsRepositoryExample.restoreArchive(
                restored, repository, ioExecutor, server::execute));

completed.whenCompleteAsync((warnings, failure) -> {
    if (failure != null) {
        logger.error("Could not persist or restore shop ECS", failure);
        return;
    }
    logger.info("Restored shop {}; diagnostics: {}", shop.id(), warnings);
}, server::execute);
```

`source` и `restored` должны быть созданы на серверном потоке;
`server::execute` должен направлять работу на этот же поток. `restored` остаётся
свежим и пустым до завершения загрузки. До завершения `completed` держите открытыми
repository, backend и executor. После завершения закрывайте ECS-менеджеры на их
owner-потоке, repository до backend, затем свой executor. Обычные DTO-операции
можно выполнять в IO executor целиком.

Не вызывайте `join/get` на серверном потоке для future, который ждёт применения
на этом же потоке. Ошибки future нужно обработать, как в callback выше.
JUnit-пример использует отдельный worker и управляемую очередь owner-потока:
сначала проверяет, что IO ещё не изменил ECS, затем выполняет owner callback.

## 8. MongoDB: изменения между серверами

Нужен запущенный replica set или sharded cluster. Подключение задаётся явно,
URI с паролями не следует хранить в исходниках:

```powershell
$env:ORG_GRADLE_PROJECT_repositoryMongoUri = 'mongodb://127.0.0.1:27017/?replicaSet=rs0'
.\gradlew.bat :core:1.20.1:repositoryMongoExample -PrepositoryMongoDatabase=sdmal_example
Remove-Item Env:ORG_GRADLE_PROJECT_repositoryMongoUri
```

`MongoRepositoryExample.run` создаёт два независимых manager, как два сервера.
Первый держит `RepositoryStorage(..., true)`, второй вставляет, обновляет и удаляет
магазин. Пример ждёт соответствующих состояний кеша и проверяет
`cache.syncFailure()`. По завершении он закрывает все handles и подписки,
печатает имя созданной коллекции; тестовый магазин удалён, база не удаляется.
Каждый запуск использует свою коллекцию `shops_example_...`.

Прямые уведомления без автоматического кеша:

```java
try (var subscription = MongoRepositoryExample.subscribe(
        shops,
        change -> {
            // Только detached данные/очередь; live ECS меняется на owner-потоке.
            logger.info("Shop invalidated: {}", change.key());
        },
        failure -> logger.error("Shop observation stopped; reload and resubscribe", failure))) {
    // Держите subscription открытой столько, сколько нужны уведомления.
}
```

Callback работает на backend worker. Событие — причина перечитать последний
вариант записи; это не готовое актуальное значение и не распределённая транзакция.
Цикл ожидания в runnable-примере служит демонстрации и не предназначен для тика мира.

## 9. Частичные обновления с проверкой ревизии

```powershell
.\gradlew.bat :core:1.20.1:repositoryPatchExample
```

`PartialUpdateRepositoryExample.run(manager)` работает с H2/JDBC или MongoDB.
Сначала он создаёт магазин, обновляет только `order`, затем намеренно пытается
записать устаревший снимок. Этот запрос получает `RepositoryConflictException`.
Пример перечитывает текущую запись и меняет `catalogId`, сохранив новый `order`.

```java
var shops = manager.openPatchable("shops", ShopRepositoryExample.DEFINITION);
var loaded = shops.findVersioned(id).orElseThrow();

try {
    var committed = shops.patch(id, loaded.revision(), change -> change.set("order", 20));
    // committed.value() — результат, committed.revision() — токен следующей правки.
} catch (RepositoryConflictException conflict) {
    var latest = shops.findVersioned(id);
    // Разрешить конфликт по правилам приложения; не повторять старую правку вслепую.
}
```

При обычной записи нужен `upsert`. Для частичной записи нужны явный patch и codec,
который выдаёт JSON-объект. Поля верхнего уровня можно установить через `set`,
передать собственный JSON через `setJson` или удалить через `unset`. Объект или
массив внутри поля заменяется целиком. `RepositoryCodec.component()` сохраняет
opaque NBT: для частичных правок нужен отдельный DTO-проектор компонента.

Режим использует отдельное хранилище: старые записи переносите через
`Repositories.copy(oldRepository, patchableRepository)` при остановленных записях.
SQL пишет только строки выбранных полей, MongoDB использует `$set`/`$unset`.
Чтение перед patch и обновление подписанного кеша пока загружают всю одну запись.
Файловые managers этот режим не поддерживают. Подробности формата и ограничений —
в [REPOSITORIES.md](REPOSITORIES.md#частичные-обновления-sql-и-mongodb).

## Проверить

```powershell
.\gradlew.bat :core:1.20.1:test --tests dev.sixik.sdmal.example.repository.RepositoryUsageExampleTest --tests dev.sixik.sdmal.example.repository.AsyncEcsRepositoryExampleTest --tests dev.sixik.sdmal.example.repository.MongoRepositoryExampleTest
```

Проверка реального Mongo-сценария включается только при заданном
`repositoryMongoUri`. Она создаёт свою базу с уникальным именем и удаляет только
её. Остальные тесты и локальный runnable-пример обходятся без MongoDB.
