# Сохранение ECS через аннотации

`AnnotatedWorldObjectComponent` сохраняет обычные Java-поля и передаёт выбранные
поля напрямую через `FriendlyByteBuf`, без `Field<T>` и явного `FieldCodec`
на каждом поле. Аннотации и адаптеры находятся в
`dev.sixik.sdmal.core.ecs.serialization`. Компонент регистрируется автоматически
сгенерированным индексом и прикрепляется с полученным дескриптором.

Для обычного компонента ручной `WorldObjectComponentType` больше не нужен:
добавьте `@EcsComponent(id = "yourmod:energy")` к public-классу с public-конструктором
без аргументов. Подключите процессор в проекте своего мода:

```groovy
dependencies {
    // Обычная зависимость от SDMAL остаётся в implementation/modImplementation.
    annotationProcessor files('libs/sdmal-ecs-processor-1.0.0.jar')
}
```

В самой SDMAL процессор уже подключён ко всем версиям и загрузчикам через
`annotationProcessor project(':ecs-processor')`. Отдельный JAR процессора не зависит
от Minecraft и подходит для Java 17/21/25. Он нужен только при компиляции; в папку
`mods` его класть не нужно. Собрать его можно задачей `:ecs-processor:buildAndCollect`.

Процессор записывает бинарные имена компонентов в
`META-INF/services/dev.sixik.sdmal.core.ecs.WorldObjectComponent` внутри JAR вашего
мода. Общий setup SDMAL читает сервисы из доступных модов и регистрирует компоненты
до загрузки миров. Java-классы в пакете `generated` не создаются, к ним не нужно
обращаться. Файлы services также не требуется редактировать вручную.

Получить дескриптор можно так:

```java
@EcsComponent(id = "yourmod:energy")
public final class EnergyComponent extends AnnotatedWorldObjectComponent {
    public EnergyComponent() {}
}

WorldObjectComponentType<EnergyComponent> type =
    WorldObjectComponentRegistry.instance().require(EnergyComponent.class);
```

`ServiceLoader` читает только сервисные дескрипторы: сканирования всех классов/JAR
в игре нет. ECS использует `ServiceLoader.Provider.type()`, поэтому конструкторы
компонентов при обнаружении не вызываются. Экземпляры создаются при добавлении
объектов или восстановлении архива. Повторное обнаружение тех же классов безопасно.

`id` должен быть стабильным namespaced-идентификатором. Процессор проверяет реализацию
`WorldObjectComponent`, public-класс и public-конструктор без аргументов; вложенный
компонент должен быть `public static` с public-внешними классами. Два одинаковых ID
в одной компиляции отклоняются процессором, конфликт между модами — при setup.
Ручная регистрация остаётся доступной для динамических фабрик. Для standalone-кода,
который не запускает SDMAL setup, один раз вызовите `registry.registerServices()`.

## Компонент и свои данные

```java
import dev.sixik.sdmal.core.ecs.EcsComponent;
import dev.sixik.sdmal.core.ecs.AnnotatedWorldObjectComponent;
import dev.sixik.sdmal.core.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.core.ecs.WorldObjectComponentType;
import dev.sixik.sdmal.core.ecs.TickableComponent;
import dev.sixik.sdmal.core.ecs.serialization.EcsData;
import dev.sixik.sdmal.core.ecs.serialization.EcsPersist;
import dev.sixik.sdmal.core.ecs.serialization.EcsSync;
import java.util.UUID;

public final class EnergyComponent extends AnnotatedWorldObjectComponent implements TickableComponent {
    @EcsPersist("energy") @EcsSync private int energy = 100;
    @EcsPersist("owner") private UUID owner;
    @EcsPersist("battery") @EcsSync private Battery battery = new Battery(0, 100);
    private int cached; // Производный кеш не сохраняется.

    @Override
    public void onTick(long tick) {
        if (energy > 0) energy--;
    }

    @EcsData
    public record Battery(
        @EcsPersist("stored") int stored,
        @EcsPersist("capacity") int capacity
    ) {
        public Battery {
            if (stored < 0 || capacity < stored) throw new IllegalArgumentException("Invalid battery state");
        }
    }
}
```

Получение дескриптора после автоматического setup:

```java
var energyType = WorldObjectComponentRegistry.instance().require(EnergyComponent.class);

// Затем на потоке мира:
var manager = LevelObjectManager.getManager(level);
manager.create().attach(new EnergyComponent(), energyType);
```

Строка в `@EcsPersist` — обязательный стабильный NBT-ключ. Переименование Java-поля
не меняет сохранения. Поддерживаются private и унаследованные поля, включая
конкретные специализации generic-базовых компонентов. Поля самого компонента
должны быть изменяемыми instance-полями: `static`, `final`, `transient`,
пустые и повторяющиеся ключи отклоняются. У record все constructor-компоненты
должны иметь `@EcsPersist`; загрузка вызывает canonical constructor и его проверки.

Схема строится и проверяется при регистрации типа, без создания компонента.
При standalone-использовании проверку можно выполнить явно:
`EcsSerializers.validateComponent(EnergyComponent.class)`. В этой реализации
используется runtime-анализ с кешированными `VarHandle`/`MethodHandle`; процессор
отвечает только за индекс компонентов. При недоступном private-доступе
через Java modules регистрация выдаёт ошибку с классом и полем; пакет должен
быть открыт модулю библиотеки. Ошибки формата при загрузке сохраняют весь объект
неактивным, по существующему контракту архива ECS.

## Поддерживаемые типы

- Все примитивы и их boxed-типы, `String`, `UUID`, enum по имени.
- `byte[]`, `int[]`, `long[]` и `CompoundTag`, с независимыми копиями NBT.
- Minecraft identifier (`ResourceLocation` в исходниках активной версии),
  `BlockPos`, `ChunkPos`, `SectionPos`.
- Records с `@EcsData`, в том числе конкретные generic-специализации.
- Точные `List<T>`, `Map<K,V>`, `Optional<T>` и вложенные комбинации.
  Списки загружаются как `ArrayList`, карты — как `LinkedHashMap`.

Например, `Map<UUID, Optional<List<EnergyComponent.Battery>>>` работает без
отдельного адаптера. Размер одной коллекции ограничен 65 536 элементами,
глубина автоматического разрешения типов — 64 уровня. Raw-типы, wildcard-типы,
произвольный `Object`, неотмеченные data-классы, полиморфные и циклические графы
не сериализуются автоматически. Для них нужен явный адаптер и собственные
правила типов/ссылок. Классы для создания из NBT не выбираются по произвольной
строке с именем Java-класса.

## Тип из сторонней библиотеки

Зарегистрируйте NBT-only адаптер **до регистрации компонента**, например для
внешнего immutable-типа `ExternalVector` с `x()`, `y()`, `z()` и конструктором
из трёх `double`:

```java
public final class ExternalVectorAdapter implements EcsSerializer<ExternalVector> {
    @Override
    public Tag write(ExternalVector value) {
        ListTag tag = new ListTag();
        tag.add(DoubleTag.valueOf(value.x()));
        tag.add(DoubleTag.valueOf(value.y()));
        tag.add(DoubleTag.valueOf(value.z()));
        return tag;
    }

    @Override
    public ExternalVector read(Tag tag) {
        if (!(tag instanceof ListTag list) || list.size() != 3
            || !(list.get(0) instanceof DoubleTag x)
            || !(list.get(1) instanceof DoubleTag y)
            || !(list.get(2) instanceof DoubleTag z)) {
            throw new EcsSerializationException("Expected three double coordinates");
        }
        return new ExternalVector(
            NbtAccess.number(x).doubleValue(),
            NbtAccess.number(y).doubleValue(),
            NbtAccess.number(z).doubleValue());
    }
}

EcsSerializers.register(ExternalVector.class, new ExternalVectorAdapter());
```

Здесь `Tag`, `ListTag`, `DoubleTag` — `net.minecraft.nbt.*`, а `NbtAccess` —
`dev.sixik.sdmal.core.ecs.codecs.NbtAccess`. Адаптер должен быть stateless/thread-safe,
не менять исходный Java-объект, возвращать не-null значение и не-null NBT без
`EndTag`. Null и защитные копии NBT обрабатывает библиотека. Для коллекций адаптер
обычно нужен только типу элемента: `List<ExternalVector>` соберётся автоматически.

Для целого generic-типа используйте точный токен:

```java
EcsSerializers.register(new TypeRef<YourContainer<UUID>>() { }, new UuidContainerAdapter());
```

`YourContainer<UUID>` и `YourContainer<String>` имеют разные регистрации. Можно
переиспользовать существующий Codec через `EcsSerializers.fromCodec(codec)`:
эта обёртка использует только его NBT-методы и сохраняет политику чтения кодека.
Встроенные, уже зарегистрированные или успешно разрешённые адаптеры заменять
нельзя: это исключает смену формата уже кешированной схемы. Если проверка схемы
не удалась из-за отсутствия адаптера, его можно добавить и повторить регистрацию.

Для особого формата одного поля:

```java
@EcsPersist("position")
@EcsUseAdapter(CompactVectorAdapter.class)
private ExternalVector position;
```

Такой класс должен реализовать `EcsSerializer<ExternalVector>` с точным типом
поля и иметь доступный no-argument constructor. Один экземпляр переиспользуется
для всех таких полей. Общая регистрация `ExternalVector` при этом не меняется.

## Значения по умолчанию, null и миграции

Отсутствующий ключ оставляет инициализированное значение поля. Явный null
очищает reference-поле; null в примитиве и неверный тип payload дают ошибку.
Все присутствующие поля декодируются до первой записи в компонент, поэтому
ошибка позднего поля не оставляет частично обновлённые обычные поля. Входной NBT
не меняется. Пользовательские адаптеры должны также избегать внешних побочных
эффектов при чтении.

Каждое значение имеет собственный compound-envelope с `$present` и payload `v`.
Это позволяет отличать null, `Optional.empty()` и отсутствующее поле и хранить
разные NBT-типы элементов в списках. Для миграций используйте
`EcsSerializers.writeValue(type, value)` / `readValue(type, tag)` вместо ручной
записи envelope; оба принимают `Class<T>` или `TypeRef<T>`.

Переход с существующего `Field<T>` на обычное поле меняет формат payload.
Увеличьте `dataVersion()` и явно преобразуйте старые значения, сохранив
остальные ключи:

```java
@Override public int dataVersion() { return 2; }

@Override
public CompoundTag migrateNbt(int savedVersion, CompoundTag nbt) {
    if (savedVersion != 1) throw new EcsSerializationException("Unsupported energy schema");
    if (!(nbt.get("energy") instanceof IntTag oldEnergy)) {
        throw new EcsSerializationException("Missing legacy energy");
    }
    int value = NbtAccess.number(oldEnergy).intValue();
    nbt.put("energy", EcsSerializers.writeValue(int.class, value));
    return nbt;
}
```

При добавлении нового поля верхнего уровня старые сохранения получают его
constructor-default. Добавление поля в record требует миграции: canonical
constructor ожидает все параметры. Неизвестные ключи payload компонента
сохраняются архивом; вложенное значение record/коллекции при записи заменяется
целиком по его текущей схеме. Для смены структуры вложенных данных также
используйте миграции версии компонента.

## Потоки и сеть

Обычные присваивания, например `energy--`, не перехватываются аннотациями.
Работайте на потоке менеджера; фоновые задачи передают изменения через
`manager.execute(...)`. NBT-операции прикреплённого компонента проверяют поток
и используют базовый state lock; при самостоятельном многопоточном доступе
mutator-методы должны использовать ту же блокировку (`runWrite`, `withWrite`).

`@EcsPersist` отвечает за NBT, `@EcsSync` — за полные двоичные сетевые снимки.
Автоматические ревизии и delta сети остаются у `SyncedWorldObjectComponent`
и `TrackedField<T>`. Старый API на `FieldCodec` сохраняется, существующие
файлы не преобразуются без явной миграции.

## Прямой сетевой формат

Выбирайте поля независимо для двух транспортов:

```java
@EcsPersist("energy") @EcsSync
private int energy = 100; // Сохраняется и отправляется; один стабильный ключ.

@EcsPersist("secret")
private String secret;   // Только файл.

@EcsSync("animation")
private int animation;   // Только сеть; явный ключ обязателен без EcsPersist.
```

Передача полного состояния:

```java
// Отправитель, на потоке владельца компонента:
serverComponent.writeNetwork(buffer);

// Получатель, после переноса обработки пакета на поток клиентского мира:
clientComponent.readNetwork(buffer);
```

Примитивы записываются нативными `writeInt`, `writeLong`, `writeDouble` и т. п.
Reference-значения получают отдельный presence-byte для null. Records,
`List<T>`, `Map<K,V>` и `Optional<T>` также используют прямой двоичный формат:
промежуточные NBT-теги и NBT-обёртка компонента не создаются. Поддерживаются
те же скаляры, UUID, enum, позиции и primitive-массивы, что перечислены выше.
`CompoundTag` автоматически не включён в бинарные типы: если нужен именно NBT
как значение, его формат следует подключить явно собственным адаптером.

Выбор record-поля означает передачу **всех** его constructor-компонентов.
Уже существующий record с `@EcsData` и ключами `@EcsPersist` работает и для сети:
для его внутренних параметров не нужно дублировать `@EcsSync`. Network-only
record вместо этого может задавать ключи параметров через `@EcsSync("key")`.
При обычном NBT-сохранении такого record его параметры всё ещё должны иметь
`@EcsPersist`.

Снимок имеет собственный протокол: `int payloadLength`, `varint version`,
`long schemaFingerprint`, `varint fieldCount`, затем значения в порядке
сортировки ключей компонента. Формат версии 1 ограничен 8 MiB на payload,
4096 полями и 65 536 элементами одной коллекции/массива. Строки ограничены
32 767 UTF-16 code units, как у соответствующего `FriendlyByteBuf.writeUtf`.
Данные после указанного frame остаются в буфере для следующего сообщения.

Fingerprint учитывает ключи, nullability, вложенные схемы records/коллекций и
версию каждого адаптера. Он вычисляется как первые 64 бита SHA-256 от канонической
схемы; это проверка совместимости, не аутентификация. Несовпадение отвергается
до изменения компонента. Переименование Java-поля и порядок объявления полей
компонента не меняют совместимость; изменение record-layout или бинарного
адаптера может её изменить. `networkSchemaFingerprint()` позволяет прочитать
этот идентификатор отдельно, он также проверяется внутри каждого frame.

При ошибке чтения поля компонента не меняются, а `readerIndex` возвращается
к началу frame. При ошибке записи откатывается `writerIndex`, ранее записанный
префикс пакета остаётся доступен. Буфер остаётся во владении вызывающего кода:
после использования он сам отвечает за `release()` и сроки жизни packet data.
Адаптеры не должны освобождать/сохранять буфер или менять его чужие области.

Это full snapshot без ревизий и подавления старых пакетов. Вызывающий мод
выбирает момент отправки, канал, получателей, object/component ID и поток
применения. Если возможна перестановка снимков, добавьте sequence в оболочку
своего пакета; `dataVersion()` относится к файлам и не мигрирует сетевой формат.
Существующий revision-based delta протокол на `TrackedField` остаётся доступен.

## Свой двоичный адаптер

Сетевой реестр независим от NBT-реестра. Для `ExternalVector` из примера выше:

```java
public final class VectorWireAdapter implements EcsNetworkSerializer<ExternalVector> {
    @Override
    public void write(FriendlyByteBuf buffer, ExternalVector value) {
        buffer.writeDouble(value.x());
        buffer.writeDouble(value.y());
        buffer.writeDouble(value.z());
    }

    @Override
    public ExternalVector read(FriendlyByteBuf buffer) {
        return new ExternalVector(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    @Override
    public String schemaName() { return "yourmod:vector/3xf64/v1"; }
}

EcsNetworkSerializers.register(ExternalVector.class, new VectorWireAdapter());
```

Регистрируйте адаптер до типа компонента или первой проверки его сетевой схемы.
Для generic-регистраций принимается тот же `TypeRef<T>`. Обязательный стабильный
`schemaName()` описывает конкретный двоичный layout; меняйте его версию вместе
с изменением байтов или смысла данных. Класс может реализовать одновременно
`EcsSerializer<T>` и `EcsNetworkSerializer<T>` и зарегистрировать экземпляр в
обоих реестрах. При передаче будут вызваны только binary-методы.

Для переопределения формата одного поля используйте отдельную аннотацию:

```java
@EcsPersist("position") @EcsSync
@EcsUseAdapter(VectorNbtAdapter.class)
@EcsUseNetworkAdapter(CompactVectorWireAdapter.class)
private ExternalVector position;
```

`@EcsUseNetworkAdapter` требует точного `EcsNetworkSerializer<T>` и доступного
no-argument constructor. `EcsNetworkSerializers.fromCodec(codec)` переиспользует
только native network-методы существующего `FieldCodec`, без вызова его NBT-методов.
Неподдерживаемый wire-тип даёт ошибку проверки схемы, автоматического fallback
в NBT нет. Сетевой путь проверяется JUnit на всех пяти Minecraft-версиях.

Сравнительный JMH с GC-профилем запускается отдельно:

```powershell
.\gradlew.bat :core:1.20.1:jmhNetwork --configure-on-demand
```

Отчёт: `core/versions/1.20.1/build/reports/jmh/ecs-network.json`. Benchmark
сравнивает полный бинарный frame с явным NBT-in-buffer для одинаковых значений;
это локальная сериализация, без доставки пакетов и нагрузки игрового мира.

Замер на JDK 17, один поток/fork, 5 прогревов и 5 измерений по секунде,
компонент с int, UUID, String и record из двух int:

| Операция | Прямой binary, ns/op | Через NBT, ns/op | Binary, B/op | Через NBT, B/op |
| --- | ---: | ---: | ---: | ---: |
| Запись | 109 | 1169 | 40 | 3704 |
| Чтение | 113 | 1232 | 232 | 5264 |

Аллокации включают boxing и создание восстановленных значений; это не zero-allocation
API. Binary-вариант также пишет/проверяет свой frame header и fingerprint, тогда
как reference передаёт только NBT для тех же значений. Цифры относятся к этой
нагрузке и машине, а не к общей производительности сети Minecraft.
