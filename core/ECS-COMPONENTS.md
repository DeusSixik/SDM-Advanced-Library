# ECS: объекты и компоненты

`EcsObject` — объект с UUID и набором компонентов. `EcsObjectManager` владеет такими
объектами: это может быть симуляция мира, серверный каталог магазина, заказы или
данные редактора. `Level`, Minecraft Entity, BlockEntity и координаты не требуются.

## Как выбрать компонент

| Требование | Основа |
| --- | --- |
| Обычные данные, логика, сохранение полей, полный сетевой снимок | `dev.sixik.sdmal.api.ecs.Component` |
| Явные `Field` / `TrackedField`, ревизии и сетевые дельты | `dev.sixik.sdmal.api.ecs.sync.NetworkComponent` |

По умолчанию наследуйтесь от `Component`. Его поля выбираются через
`@EcsPersist("stable_key")` и `@EcsSync`; вспомогательные поля не сохраняются.
`@EcsComponent(id = "mymod:catalog")` регистрирует тип через annotation processor и
ServiceLoader. Базовый класс называется `Component`, чтобы он не конфликтовал
с именем аннотации `EcsComponent` в Java imports.

Тики включаются интерфейсом, а не ещё одним базовым классом:

- `TickableComponent`: обычный `onTick(long)` на потоке менеджера.
- `SnapshotTickableComponent<S,R>`: `capture` / `compute` / `apply`, с выполнением
  чистого вычисления на workers согласно ограничениям scheduler.

Не реализуйте оба tick-интерфейса одновременно. Компоненты только с данными не
нуждаются в тиках, отдельной системе или доступе к миру. `onAttached`, `onDetached`
и `priority` доступны в обоих базовых классах. Порядок тиков определяется
`priority`, затем порядком прикрепления.

`RenderStateComponent<S>` остаётся специализированным наследником `Component` в
Minecraft-пакете для публикации immutable render-state. Отдельного аналога
Unity ScriptableObject сейчас нет: общие неизменяемые определения можно хранить
отдельно от состояния экземпляра. Asset lifecycle и редакторские ресурсы — отдельная
задача, они не добавляются в базовый компонент.

## Пример магазина без мира

```java
import dev.sixik.sdmal.api.ecs.Component;
import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;

@EcsComponent(id = "mymod:catalog")
public final class CatalogComponent extends Component {
    @EcsPersist("catalog_id") @EcsSync
    private String catalogId = "default";

    public CatalogComponent() {}

    public void rename(String value) {
        checkOwnerThread();
        catalogId = value;
    }
}
```

После общего setup SDMAL:

```java
var shops = new EcsObjectManager();
var shop = shops.create();
shop.attach(new CatalogComponent());

var catalog = shop.getSingle(CatalogComponent.class).orElseThrow();
catalog.rename("building");

CompoundTag snapshot = shops.writeNbt();
// Файл и расписание сохранения принадлежат приложению.

var restored = new EcsObjectManager();
var warnings = restored.readNbt(snapshot); // Только свежий пустой менеджер.

restored.close();
shops.close();
```

`attach(component)` получает уже зарегистрированный тип аннотированного класса,
поэтому применяются duplicate policy и сохранение. Обнаружение нужно выполнить до
этого вызова. SDMAL делает это при setup; самостоятельный код внутри Minecraft
runtime может вызвать `ComponentRegistry.instance().registerServices()` один раз.
Аннотация не заменяет подключение `sdmal-ecs-processor` в `annotationProcessor`.

Неаннотированные runtime-компоненты можно прикреплять без типа; для архива им
нужен явный зарегистрированный `ComponentType`, либо объект помечается временным.
Ручные фабрики и `attach(component, type)` остаются доступны.

Компилируемый пример: `example/ecs/shop/CatalogComponent.java` и
`example/ecs/shop/ShopEcsExample.java`. Он не зависит от позиции, измерения или тиков.
ECS остаётся частью Minecraft-библиотеки: NBT и FriendlyByteBuf требуют её runtime;
«без мира» не означает отдельный независимый от Minecraft JAR.

## Потоки и синхронизация

Менеджер создаётся, изменяется, сохраняется и закрывается на одном owner-потоке.
Вызывайте `checkOwnerThread()` в методах доступа к изменяемым данным компонента.
Из другого потока передавайте изменения через `manager.execute(...)`. Если
приложению нужны тики и выполнение этих команд, оно вызывает `tickAll()` само.
Для самостоятельных объектов без менеджера координация остаётся обязанностью caller.

`manager.context()` возвращает `EcsContext`. `enqueue(phase, Runnable)` работает
без мира; `level()` и `enqueue(phase, level -> ...)` требуют `bindWorld(level)`.
Мировой bridge автоматически связывает менеджер с Level и обслуживает его тики.

`Component.writeNetwork/readNetwork` кодируют полный снимок `@EcsSync` через
FriendlyByteBuf. Аннотация не отслеживает присваивания и не отправляет пакеты сама.
В `NetworkComponent` эти методы используют tracked full-format, а `writeDelta`
и `readDelta` работают с отдельным `DeltaTracker` каждого получателя. Обычные
`field(...)` сохраняются только в NBT; `tracked(...)` также участвуют в сети.
Обычные `@EcsSync` поля выбирайте в `Component`, явные сетевые handles — в
`NetworkComponent`. Доставка, подписки и выбор адресатов принадлежат приложению.
Блокировки одного tracked-поля не делают несколько полей транзакцией и не дают
права обращаться к Minecraft API из worker-потока.

Пакеты могут содержать поле конкретного зарегистрированного компонента. Пакетный
кодек использует фабрику из `ComponentRegistry`, передаёт полный снимок `@EcsSync`
полей и создаёт на получателе новый отсоединённый компонент. Компонент должен быть
зарегистрирован с обеих сторон до первого кодирования/декодирования пакета:

```java
@NetworkPacket(id = "mymod:shop_snapshot", side = Side.CLIENT)
public record ShopSnapshotS2CPacket(UUID shopId, ShopComponent shop)
        implements Packet {
    @Override
    public void handle(PacketContext context) {
        ShopClientMirror.apply(shopId, shop);
    }
}

// На серверном потоке после открытия магазина или успешной покупки:
new ShopSnapshotS2CPacket(shop.id(), shop.getSingle(ShopComponent.class).orElseThrow())
        .send(player);
```

Тип поля пакета должен быть конкретным зарегистрированным классом, например
`ShopComponent`, а не базовым `Component`: декодеру нужна фабрика для создания
экземпляра. Синхронизируются только поля с `@EcsSync`; локальные поля компонента
принимают значения конструктора по умолчанию. Отправляйте пакет на потоке-владельце
компонента. Клиенту следует применять полученный компонент к своей ECS-проекции, а
изменения магазина принимать только как проверенные сервером запросы.

## Переход со старого API

| Раньше | Теперь |
| --- | --- |
| `WorldObject` | `EcsObject` |
| `WorldObjectManager` | `EcsObjectManager` |
| `WorldObjectComponent`, `AbstractWorldObjectComponent`, `AnnotatedWorldObjectComponent` | `Component` |
| `FieldedWorldObjectComponent`, `SingleThreadedWorldObjectComponent`, `SyncedWorldObjectComponent` | `NetworkComponent` для старых handles; `Component` для обычных аннотированных полей |
| `PersistentWorldObjectComponent` | Методы NBT и миграции в `Component` |
| `WorldObjectComponentType` / `WorldObjectComponentRegistry` | `ComponentType` / `ComponentRegistry` |
| `WorldContext`, `manager.worldContext()` | `EcsContext`, `manager.context()` |
| `HostedWorldObject` | `HostedEcsObject` |
| `LevelObjectManager` / `WorldObjectManagerApi` | `LevelEcsAccess` / `LevelEcsManagers` в `.minecraft` |

Это изменение исходного и бинарного API: зависимые моды нужно пересобрать.
Старых базовых классов-алиасов нет. Идентификаторы компонентов, NBT-ключи,
UUID объектов и форматы аннотированных/TrackedField данных сохранены; пустые
version-zero записи старых ownership-компонентов читаются новым `Component`.
Сервисный дескриптор теперь называется
`META-INF/services/dev.sixik.sdmal.api.ecs.Component`: используйте новый processor.

`SingleThreadedWorldObjectComponent` больше не фиксирует поток по первому обращению.
Все управляемые компоненты следуют потоку менеджера; unmanaged-код обеспечивает
собственную координацию. Способность к параллельному compute задаётся интерфейсом
и аннотациями scheduler, а не названием базового класса.

## Проверка

`./gradlew.bat buildAll` собирает 14 вариантов загрузчиков и запускает тесты ядра
на пяти версиях Minecraft. Проверки новой модели включают каталог без Level,
автоматическое прикрепление и duplicate policy, поток владельца, чтение старой
version-zero записи и full/delta обмен через `NetworkComponent`.

Сравнительные JMH-нагрузки тиков и дельт:

```powershell
./gradlew.bat :core:1.20.1:jmhCleanup '-PjmhCases=owner:128:0,delta:128:0' -PjmhReport=ecs-component-model
```

Используются 5 прогревов, 5 измерений, 1 fork и GC profiler. JSON находится в
`core/versions/1.20.1/build/reports/jmh/ecs-component-model.json`. Сравнение идёт
с историческим снимком до прежней оптимизации; перед замерами проверяются
состояние и контрольные суммы данных. В прогоне 2026-11-18 временная погрешность
велика, поэтому он не подтверждает ускорение от текущего изменения API.

Отдельно `./gradlew.bat :core:1.20.1:jmhCollections` сравнивает текущую
реализацию со снимком непосредственно перед переходом на fastutil и
`ObjectArrayView`. Отчёт: `core/versions/1.20.1/build/reports/jmh/ecs-collections.json`.

| Нагрузка | До, мкс/операцию | После, мкс/операцию |
| --- | ---: | ---: |
| Тик 128 компонентов | 5,370 ± 0,096 | 5,463 ± 0,131 |
| Чтение `components().size()` для 1024 объектов | 1,602 ± 0,039 | 1,637 ± 0,031 |
| Запись и чтение 128 сетевых дельт | 4,276 ± 0,385 | 3,954 ± 0,119 |

Интервалы JMH (99,9%) перекрываются во всех сценариях. GC profiler показал
менее 1 Б/операцию по средним значениям в обоих вариантах. Эти сценарии
измеряют чтение готовых снимков и обработку данных; создание снимков при
добавлении и удалении компонентов здесь не измеряется.
