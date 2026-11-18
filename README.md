# SDMAL

Автоматическая регистрация предметов, блоков, BlockEntity и вкладок через
`@RegisterContent`: [контракт, примеры и порядок во вкладках](core/REGISTER-CONTENT.md).

Автоматические C2S/S2C пакеты с `@NetworkPacket`, `Packet.send()` и бинарной
сериализацией полей: [API, примеры и подключение processor](core/NETWORK-PACKETS.md).

Там же описаны `@NetworkRequest` с `CompletableFuture` и фрагментация больших
сообщений до 32 МиБ.

Мультиверсионный проект на Stonecutter 0.10. Gradle запускается на JDK 25;
Java toolchains автоматически выбирают Java 17, 21 или 25 для целевой версии.

| Minecraft | Java | Fabric | Forge | NeoForge |
| --- | --- | --- | --- | --- |
| 1.19.2 | 17 | Да | Да | — |
| 1.20.1 | 17 | Да | Да | Да, legacy API |
| 1.21.1 | 21 | Да | Да | Да |
| 26.1.2 | 25 | Да | Да | Да |
| 26.3 | 25 | Да | Да | Да |

NeoForge для 1.19.2 не существует. Названия 1.19, 1.20 и 1.21 в матрице
представлены версиями 1.19.2, 1.20.1 и 1.21.1 соответственно.

## Модули

- `core` — ядро, общие API, ресурсы, общие миксины и JUnit-тесты.
  Общие Java-библиотеки подключаются в `core/build.gradle` через `api` / `implementation`.
- `impl` — реализации, зависящие от ядра, и их миксины. Зависимости реализаций
  подключаются в `impl/build.gradle` через `api` / `implementation`.
- `fabric` — точки входа, регистрация и зависимости Fabric.
- `forge` — точки входа, регистрация, TOML-конфигурация и зависимости Forge.
- `neoforge` — соответствующая интеграция NeoForge, включая legacy 1.20.1.

Каждый модуль содержит `src/` с исходниками активной версии и `versions/<версия>/`
с её зависимостями. Исходники в `versions/*/src/` генерирует Stonecutter;
редактировать нужно исходники модулей. Условия `//? if ...` адаптируют API между версиями.

Все модули загрузчиков зависят от соответствующих `:core:<версия>` и `:impl:<версия>`.
`impl` зависит от `core`; обратной зависимости нет.
Обработанные Stonecutter исходники обоих модулей компилируются вместе с исходниками
каждого загрузчика, чтобы разработческие запуски Forge не создавали конфликт Java-модулей.
Общие классы и ресурсы включаются в итоговый JAR каждого загрузчика;
отдельно устанавливать JAR `core` или `impl` в игру не нужно. Подключение общей зависимости
обеспечивает её присутствие в classpath, но для встраивания сторонней библиотеки
в распространяемый мод нужно дополнительно настроить Jar-in-Jar конкретного загрузчика.

## Миксины и реализации по версиям

Сборка автоматически создаёт два независимых конфига:

- `sdmal.core.mixins.json` — миксины ядра из пакета `dev.sixik.sdmal.core.mixin` и его подпакетов.
- `sdmal.impl.mixins.json` — миксины реализаций из пакета `dev.sixik.sdmal.impl.mixin`
  и его подпакетов, включая миксины в модулях конкретных загрузчиков.

Оба конфига уже подключены в метаданных Fabric, Forge и NeoForge. Редактировать
сгенерированные JSON или вручную перечислять классы в них не нужно.
Для Forge 1.19.2/1.20.1 и NeoForge 1.20.1 сборка также создаёт общий
`sdmal.refmap.json` и применяет дополнительные mappings для `@Shadow`/`@Overwrite`.
При запуске Forge 1.19.2/1.20.1 из Gradle автоматически создаются обратные
SRG → official mappings и включается remap refmap для среды разработки.
Fabric обрабатывает ссылки миксинов при remap JAR.

Общие исходники из `src/main/java` компилируются во все целевые версии.
Общие миксины добавляйте в соответствующий пакет:

```text
core/src/main/java/dev/sixik/sdmal/core/mixin/MyMixin.java
impl/src/main/java/dev/sixik/sdmal/impl/mixin/MyMixin.java
```

Для миксина достаточно стандартной аннотации `@Mixin`:

```java
package dev.sixik.sdmal.impl.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;

/** Общая реализация для всех целевых версий сервера. */
@Mixin(MinecraftServer.class)
public abstract class ServerMixin {
    // Здесь располагаются ваши @Inject, @Accessor и другие изменения.
}
```

Для **трёх разных реализаций** одного миксина удобнее сохранить одно имя класса
в трёх отдельных каталогах:

```text
impl/src/versioned/
  1.19-1.21/java/dev/sixik/sdmal/impl/mixin/MyMixin.java
  26.1/java/dev/sixik/sdmal/impl/mixin/MyMixin.java
  26.3/java/dev/sixik/sdmal/impl/mixin/MyMixin.java
```

Название папки — числовой префикс версии или включительный диапазон префиксов.
`1.19-1.21` включает все патчи до 1.21.x, `26.1` включает 26.1.2.
Сравнение числовое: `26.3` не совпадает с `26.30`.
В сборку 1.19.2, 1.20.1 и 1.21.1 попадёт первый класс, в 26.1.2 — второй,
в 26.3 — третий. Подходящий каталог выбирается **до javac**: другие реализации
могут использовать отсутствующие в текущей версии импорты, методы и сигнатуры.
`core` и модули загрузчиков поддерживают тот же `src/versioned/<селектор>/java/`.
Обычные Java-классы реализаций также можно хранить там.

У каждого варианта миксина остаётся стандартная аннотация `@Mixin`.
Если две подходящие реализации имеют одинаковое полное имя, сборка сообщает ошибку.
Не размещайте тот же класс одновременно в `src/main/java` и подходящем версионном
каталоге: общий класс не заменяется автоматически.

Сторона выполнения определяется непосредственным подпакетом пакета миксинов:

```text
dev.sixik.sdmal.core.mixin.client.*  -> client
dev.sixik.sdmal.core.mixin.server.*  -> server
dev.sixik.sdmal.impl.mixin.client.*  -> client
dev.sixik.sdmal.impl.mixin.server.*  -> server
```

Остальные пакеты под `core.mixin` и `impl.mixin` регистрируются в общей секции
`mixins` и применяются на клиенте и сервере. Например, `impl.mixin.client.render`
попадёт в `client`, а `impl.mixin.feature.client` — в общую секцию.
Это правило действует и для общих, и для версионных исходников.

Конфиг выбирается по Java-пакету, независимо от модуля исходников:
`dev.sixik.sdmal.core.mixin.*` попадает в `sdmal.core.mixins.json`,
`dev.sixik.sdmal.impl.mixin.*` — в `sdmal.impl.mixins.json`.
Например, реализация ECS может лежать в
`core/src/main/java/dev/sixik/sdmal/impl/mixin/ecs/`: она соберётся вместе с `core`,
а зарегистрируется в конфиге `impl`. Папка `impl/src/main/java` также поддерживается.

Используйте один миксин верхнего уровня на Java-файл.
Условия Stonecutter `//? if ...` обрабатываются в обычном `src/main/java`.
Каталоги `src/versioned` содержат готовые Java-реализации и не требуют переключения
активной версии; Stonecutter не переписывает их при смене версии проекта.
Все исходники, помеченные стандартным `@Mixin`, сборка автоматически заносит
в соответствующий конфиг по пакету Java. JSON не нужно создавать или редактировать
вручную. Сборка помещает готовые конфиги и исходники в
`<модуль>/versions/<версия>/build/generated/mixinSelection/`;
открывать и править нужно исходники в `src/main/java` или `src/versioned`,
а конфиги будут пересозданы при следующей сборке.

Название каталога выбирает реализацию; вызовы Minecraft API пишутся в соответствующем
исходнике.

## ECS

Публичный API: `dev.sixik.sdmal.api.ecs`, аннотации: `api.ecs.annotations`.
Карта пакетов и переход со старых импортов: [docs/ECS-API.md](docs/ECS-API.md).
План и критерии рефакторинга: [docs/ECS-CLEANUP-PLAN.md](docs/ECS-CLEANUP-PLAN.md).
Выполненный рефакторинг, JMH до/после и ограничения результатов:
[docs/ECS-PERFORMANCE.md](docs/ECS-PERFORMANCE.md).

Машины на ECS: [core/ECS-BLOCK-ENTITIES.md](core/ECS-BLOCK-ENTITIES.md).
`EcsBlockEntity` связывает объект с чанком, сохраняет его данные и синхронизирует
клиент. `BlockEntityComponent` даёт координаты/dirty/update, а
`RenderStateComponent` — immutable данные renderer. Симуляцию запускает ECS мира.
Готовый пример доступен во вкладке SDMAL или через `/give @s sdmal:ecs_generator`.
ПКМ по установленному генератору показывает в чате энергию и номер тика ECS.
Пример мультиблока: [core/ECS-MULTIBLOCK-EXAMPLE.md](core/ECS-MULTIBLOCK-EXAMPLE.md).
`/give @s sdmal:ecs_multiblock_controller`: восемь железных блоков вокруг
контроллера и блок редстоуна над ним; ПКМ показывает сборку, энергию и недостающую часть.
Shift+ПКМ с `sdmal:example_item` по контроллеру автоматически достраивает структуру
в свободных позициях без расхода материалов.

Подсистема объектов и компонентов перенесена из Artisans-Delight в
`core/src/main/java/dev/sixik/sdmal/api/ecs/`.
Она включает менеджер объектов, типы компонентов, lifecycle, поля, NBT-кодеки
и full/delta синхронизацию. Основные имена классов сохранены.

Примеры компонентов, подключение к Level, протокол синхронизации и список
исправлений описаны в [core/ECS.md](core/ECS.md). JMH-бенчмарки находятся отдельно
в `core/src/jmh/java` и не включаются в мод.

Реализация ECS в `impl` автоматически тикает менеджер после тика каждого
серверного и клиентского мира через Mixin; отдельный вызов `tickAll()` для обычной
интеграции не требуется.

План развития и границы текущих изменений: [core/ECS-PLAN.md](core/ECS-PLAN.md).
Менеджер владеет своими объектами и изменяется только в потоке создания;
для фоновых задач используйте `execute(...)`. Доступны сон/пробуждение объектов,
интервалы тиков и запросы `query(...)`/`queryTypes(...)`.

Серверные миры автоматически сохраняют ECS в отдельный NBT-файл каждого
измерения с резервной копией. Поддерживаются миграции схем компонентов и
сохранение неизвестных записей. Типы компонентов нужно зарегистрировать до
загрузки миров и передавать в `attach(component, type)`; временные объекты
помечайте `setPersistent(false)`. Примеры и формат — в [core/ECS.md](core/ECS.md).
Для обычных полей без `Field`/`Codec` доступен `AnnotatedWorldObjectComponent`:
`@EcsPersist`, records с `@EcsData` и реестр адаптеров пользовательских типов.
Для компонента достаточно public-класса с `@EcsComponent(id = "modid:name")` и
public-конструктора без аргументов. `sdmal-ecs-processor` в `annotationProcessor`
добавляет компоненты в `META-INF/services`, а общий setup загружает их через
`ServiceLoader`. Обращаться к generated-классам и вызывать регистрацию вручную не нужно.
Небольшой пример — в [core/ECS-EXAMPLE.md](core/ECS-EXAMPLE.md),
полное руководство — в [core/ECS-ANNOTATIONS.md](core/ECS-ANNOTATIONS.md). `@EcsSync`
отправляет выбранные поля напрямую через `FriendlyByteBuf`; сетевые адаптеры
подключаются через `EcsNetworkSerializers`. Ревизии и delta остаются у `TrackedField`.

## Сборка

Команды для PowerShell, из корня проекта:

```powershell
.\gradlew.bat :buildAll
.\gradlew.bat :buildFabric
.\gradlew.bat :buildForge
.\gradlew.bat :buildNeoForge
.\gradlew.bat :fabric:1.20.1:buildAndCollect
.\gradlew.bat :forge:26.3:buildAndCollect
.\gradlew.bat :core:1.20.1:test
.\gradlew.bat -p buildSrc test
```

Готовые моды и исходники собираются в `build/libs/1.0.0/`.
Имена файлов содержат загрузчик и версию Minecraft. Сборки Forge 1.19.2/1.20.1
и NeoForge 1.20.1 проходят обязательную реобфускацию.

Для переключения версии используйте задачи `Set active project to …` в Gradle
или плагин Stonecutter для IntelliJ IDEA. Например:

```powershell
.\gradlew.bat ":Set active project to 26.3"
.\gradlew.bat ":Reset active project"
```

Переключение синхронизировано между модулями.
Текущая версия зафиксирована в `stonecutter.gradle`; исходная версия — 1.20.1.

Запуск игры: `:fabric:<версия>:runClient`, `:forge:<версия>:runClient`
или `:neoforge:<версия>:runClient`. Для сервера используется `runServer`.
Для фаз и параллельных систем используйте `WorldObjectManager.scheduler()` и
`worldContext()`; контракт и пример находятся в [core/ECS-PARALLEL-PLAN.md](core/ECS-PARALLEL-PLAN.md)
и разделе «Фазы и параллельные системы» в [core/ECS.md](core/ECS.md).
Обычному компоненту достаточно `SnapshotTickableComponent<S,R>` с методами
`capture/compute/apply`: менеджер автоматически группирует и запускает его,
без отдельного класса системы и ручной регистрации в scheduler.
Forge/NeoForge сохраняют параметры в `config/sdmal-common.toml`,
Fabric — в `config/sdmal-common.json` с теми же ключами и значениями по умолчанию.

Первый импорт загружает и подготавливает Minecraft для всех версий и может занять
несколько минут. Для адресной проверки одного модуля можно добавить
`--configure-on-demand` к команде с полным путём задачи.
