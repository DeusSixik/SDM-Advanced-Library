# ECS

Пакет `dev.sixik.sdmal.core.ecs` перенесён из
[Artisans-Delight, ветка 1.21.1-main](https://github.com/DeusSixik/Artisans-Delight/tree/88ddc9e13e78a922f2605e0388680b9d678c632e/common/src/main/java/dev/sixik/artisans_delight/libs/world).
Исходный commit: `88ddc9e13e78a922f2605e0388680b9d678c632e`.
Сохранены имена основных классов и формат сетевых full/delta сообщений.
Лицензия и происхождение включаются в JAR в `META-INF/sdmal-ecs/`.

## Компонент и тики

```java
import dev.sixik.sdmal.core.ecs.TickableComponent;
import dev.sixik.sdmal.core.ecs.WorldObject;
import dev.sixik.sdmal.core.ecs.WorldObjectComponentType;
import dev.sixik.sdmal.core.ecs.WorldObjectManager;
import dev.sixik.sdmal.core.ecs.sync.FieldCodecs;
import dev.sixik.sdmal.core.ecs.sync.SyncedWorldObjectComponent;
import dev.sixik.sdmal.core.ecs.sync.TrackedField;

public final class CounterComponent extends SyncedWorldObjectComponent implements TickableComponent {
    public final TrackedField<Integer> count = tracked("count", FieldCodecs.intValue(), 0);

    @Override
    public void onTick(long tick) {
        count.update(value -> value + 1);
    }
}

// Инициализация типа; при необходимости регистрируется в WorldObjectComponentRegistry.instance().
var counterType = new WorldObjectComponentType<>("yourmod:counter", CounterComponent.class,
    WorldObjectComponentType.DuplicatePolicy.REJECT, CounterComponent::new);

WorldObjectManager manager = new WorldObjectManager();
WorldObject object = manager.create();
CounterComponent counter = object.attach(new CounterComponent(), counterType);
manager.tickAll();
int value = counter.count.get(); // 1
manager.close(); // Отсоединяет компоненты и запрещает последующие add/create.
```

`WorldObject` — контейнер компонентов, а не Minecraft Entity. `WorldObjectManager`
хранит объекты по UUID. Тики идут по `priority()`, затем по порядку прикрепления.
`onAttached()` вызывается после публикации компонента, `onDetached()` — после его
удаления из нового snapshot. Ошибка одного компонента не останавливает остальные:
исключения собираются и выбрасываются после обхода.

Изменение состава во время тика видно следующему snapshot. `object.defer(...)`
выполняет работу после компонентов на потоке, вызвавшем `tick()`; это не executor.
Менеджер принадлежит потоку, в котором создан: регистрация, удаление, изменение
состава его объектов, тики и close выполняются только там. Фоновые потоки
используют `manager.execute(...)`. Сами значения tracked-полей могут обновляться
с других потоков по их контракту; это не делает Minecraft API потокобезопасным.

## Владение, команды и активность

Один объект принадлежит одному менеджеру. Повторная регистрация в другом менеджере
отклоняется. `object.detach()` управляемого объекта также удаляет его из менеджера;
detach окончателен, перенос уже отсоединённого объекта не поддерживается.

```java
// Фоновый поток передаёт работу игровому потоку.
manager.execute(() -> object.attach(new CounterComponent()));

object.setTickInterval(20); // Первый ближайший тик, затем раз в 20 тиков менеджера.
object.sleep();            // Компоненты и object.defer(...) ждут пробуждения.
manager.execute(object::wake); // Команды менеджера работают и для спящих объектов.

var counters = manager.query(CounterComponent.class);
// Несколько классов: manager.query(PositionComponent.class, VelocityComponent.class).
// По точным дескрипторам: manager.queryTypes(positionType, velocityType).
```

Порядок объектов — порядок регистрации, компонентов — priority и attach sequence.
Новые объекты из команд начинают тикать не раньше следующего manager tick;
изменения во время обхода не добавляют объект в текущий snapshot. Запросы включают
спящие объекты, возвращают неизменяемый список и сканируют membership snapshot;
это запросы по составу, а не архетипный индекс или транзакция всех компонентов.

Очередь команд вмещает 4096 элементов, бюджет по умолчанию — 256 за тик
(`setCommandBudget`). Очередь `object.defer` вмещает 1024 задачи, за один вызов
исполняется до 256 (`drainDeferred(int)` позволяет задать другой бюджет).
Новая работа, добавленная во время обработки очереди, ждёт следующую обработку.
Переполнение и отправка команд закрытому менеджеру дают `RejectedExecutionException`.
Close отбрасывает ожидающую работу. Медленная отдельная задача не прерывается
принудительно: лимит ограничивает количество задач, не время выполнения.

Прямое изменение структуры управляемого объекта с чужого потока теперь даёт
`IllegalStateException`. Это намеренное уточнение API; замените такие вызовы
на команды менеджера. Для standalone WorldObject остаются snapshot/lock механизмы,
но его жизненный цикл должен координировать вызывающий код.

## Поля, NBT и сеть

Объявляйте поля в конструкторе/инициализаторе. После прикрепления или первого
обращения к схеме добавлять поля нельзя. Имена уникальны в пределах компонента.

- `AbstractWorldObjectComponent` — базовая ownership-логика и read/write lock helpers.
- `FieldedWorldObjectComponent` — декларативные поля и автоматическое сохранение NBT.
- `SingleThreadedWorldObjectComponent` — обычные `Field<T>` и проверка потока.
  NBT-операции проверяют поток автоматически; в своих методах вызывайте `checkThread()`.
- `SyncedWorldObjectComponent` — `TrackedField<T>` с ревизиями и сетевыми дельтами.
  Обычные `field(...)` в нём сохраняются в NBT, но не передаются по сети.

```java
CompoundTag saved = new CompoundTag();
counter.writeNbt(saved);
CounterComponent restored = new CounterComponent();
restored.readNbt(saved);
```

Отсутствующие или неверно типизированные скалярные NBT-поля сохраняют текущее
значение компонента. `FieldCodecs` содержит числа, строки, UUID, compound,
optional, list, map и enum. `ExternFieldCodecs` — identifier, BlockPos,
ChunkPos и SectionPos. Изменения Minecraft NBT/ChunkPos/Identifier API изолированы
в коде совместимости и обрабатываются Stonecutter.

Для сетевого обмена один `DeltaTracker` хранится на отправителе для каждого
получателя, второй — на получателе. До первого обмена сравните
`schemaFingerprint()` и согласуйте тип компонента. Порядок `tracked()`-полей и
стабильные `schemaName()` кодеков — часть протокола. После успешного full-sync:

```java
serverComponent.writeFull(fullBuffer);
long[] receivedRevisions = clientComponent.readFullWithRevisions(fullBuffer);
senderTracker.reset(receivedRevisions);
receiverTracker.reset(receivedRevisions);

serverComponent.count.set(42);
if (serverComponent.writeDelta(deltaBuffer, senderTracker)) {
    clientComponent.readDelta(deltaBuffer, receiverTracker);
}
```

Пример изображает обе стороны в одном процессе. В реальной сети получатель
подтверждает отправителю full-sync или использует надёжный упорядоченный канал.
`writeDelta` меняет sender tracker после успешной записи в буфер, а не после
доставки пакета. При потере/отбрасывании пакета нужен повторный full-sync или
внешний протокол подтверждения. Если `writeDelta` возвращает `false`, буфер не
меняется. Если кодек выбросил исключение при записи, отбросьте частично записанный
буфер; tracker остаётся на предыдущих ревизиях.

Значение и ревизия каждого поля захватываются вместе под блокировкой. Snapshot
не является общей транзакцией всех полей: связанные значения объявляйте одним
полем с составным кодеком. Дельты с устаревшей ревизией потребляются и игнорируются.
Пакет полностью декодируется до применения значений; кодеки с собственными
побочными эффектами или бросающим `copy()` всё равно требуют аккуратного использования.
`DeltaTracker` сам по себе не потокобезопасен.

`Field.get()` возвращает живую ссылку. Для mutable-значений используйте copy-on-write
через `update()` и кодек с глубоким `copy()`; изменение полученной коллекции напрямую
обходит ревизии и не поддерживается.

## Привязка к Minecraft Level

```java
WorldObjectManager manager = LevelObjectManager.getManager(level);
CounterComponent counter = manager.create().attach(new CounterComponent());
// CounterComponent.onTick(...) вызывается автоматически после тика этого мира.
```

Пакет `core.ecs` предоставляет библиотечный API. В реализации `impl` ECS-миксины
автоматически создают менеджер при конструировании `Level`, вызывают `tickAll()`
после каждого `ServerLevel.tick` и `ClientLevel.tick`, а при `Level.close()`
или `ClientLevel.disconnect()` удаляют менеджер. При `/tick freeze` (версии с TickRateManager) ECS тоже
останавливается. Ошибки ECS-тиков записываются в лог после попытки тика всех
объектов и не прерывают ванильный тик. `LevelObjectManager` оставлен как
bridge-интерфейс для этой интеграции и собственных хуков.

Для менеджера обычного Minecraft-мира не вызывайте `tickAll()` вручную: это даст
дополнительный тик. На сервере работа выполняется в серверном потоке, на клиенте —
в клиентском; пауза одиночной игры останавливает тики вместе с миром. Менеджер
тикает целиком, независимо от загрузки чанков и расстояния до игрока. Компонент
должен реализовывать `TickableComponent`, чтобы получать `onTick(long)`.
Аргумент — локальный счётчик менеджера, не `gameTime` и не время суток.

Отдельный `new WorldObjectManager()` не привязан к миру: его тикает владелец.
Хранение файлов и передача сетевых пакетов остаются на вызывающем моде.
При смене измерения без disconnect и в нестандартных путях выгрузки вызывайте
`WorldObjectManagerApi.unregisterWorld(level)` из unload-события загрузчика,
если путь обходит Minecraft-методы ниже. Обычная замена/очистка клиентского мира
перехватывается в `Minecraft.updateLevelInEngines`: старый менеджер закрывается,
повторная установка того же мира его не закрывает. Серверная очистка ECS проходит
в начале `Level.close()`, поэтому исключение при закрытии ресурсов не пропускает её.
Менеджеры привязаны к экземпляру Level, а не только к dimension key: два мира
одной размерности и клиент/сервер не разделяют состояние. Слабые ключи служат
резервом, если компоненты сами не удерживают Level; обязательную очистку callback
обеспечивает явный unload.

Схемы NBT-полей, tracked-полей и fingerprint кешируются при первом обращении.
После фиксации схемы менять имена полей и `codec.schemaName()` нельзя.
Нормальные ошибки компонентов агрегируются; лог содержит первый stack trace и
каждый сотый подряд неуспешный тик. Успешный тик сбрасывает счётчик. Фатальные
ошибки VM, ThreadDeath и LinkageError немедленно выходят наружу.

## Отличия от исходника

- Удалён флаг генерации дорожной сети: это состояние Artisans-Delight, не ядра ECS.
- После `close()` менеджер не принимает объекты; ошибки listener не прерывают очистку остальных.
- Захват значения/ревизии и продвижение tracker исправлены, чтобы изменения во время записи не терялись.
- Codec-builder фиксирует переданные функции в момент `build()`.
- Явное пустое optional записывается в NBT через wrapper `{$present: false}`;
  непустое — через `{$present: true, v: ...}`. Чтение поддерживает прежнюю прямую
  запись значения. Сетевой формат optional сохраняется.
- NBT и позиции поддерживают все настроенные версии проекта.
- Исходное консольное демо заменено тестами и примером в этом документе.

## Проверки

```powershell
.\gradlew.bat :core:1.20.1:test
.\gradlew.bat :buildAll
.\gradlew.bat :core:1.20.1:jmh --configure-on-demand
```

JMH изолирован в `core/src/jmh/java`, не входит в мод и запускается отдельно.
Результаты с профилем аллокаций: `core/versions/1.20.1/build/reports/jmh/ecs.json`.
Оптимизация структур данных под конкретную нагрузку требует отдельных измерений;
полный игровой мир в этом бенчмарке не моделируется.

Замеры текущей реализации на JDK 17 (128 объектов, одинаковая нагрузка): обход
старой map-структуры — около 733 нс/56 B на тик, стабильный активный массив —
около 637 нс/практически 0 B. Повторный доступ к кешированной схеме не создаёт
список, прежний `List.copyOf` в этом сценарии создаёт около 48 B за обращение.
Это локальный JMH-бенчмарк, а не оценка общей производительности игрового мира.
