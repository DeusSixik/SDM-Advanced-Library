# BlockEntity на ECS

`EcsBlockEntity` — оболочка Minecraft для объекта ECS. Блок и `BlockEntityType`
регистрируются обычным способом загрузчика. Оболочка хранит данные в чанке,
восстанавливает компоненты и получает клиентские обновления. Машинная логика
остаётся в компонентах; отдельный `BlockEntityTicker` не нужен.

Поддерживаются 1.19.2, 1.20.1, 1.21.1, 26.1.2 и 26.3. Отличия NBT API
обрабатываются Stonecutter внутри оболочки.

## Минимальная оболочка

```java
public final class MachineBlockEntity extends EcsBlockEntity {
    public MachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MACHINE.get(), pos, state);
    }

    @Override
    protected void createComponents(WorldObject object) {
        object.attach(new MachineComponent(),
                WorldObjectComponentRegistry.instance().require(MachineComponent.class));
    }
}
```

Импорты оболочки и компонентов хоста:

```java
import dev.sixik.sdmal.api.ecs.minecraft.blockentity.EcsBlockEntity;
import dev.sixik.sdmal.api.ecs.minecraft.blockentity.BlockEntityComponent;
import dev.sixik.sdmal.api.ecs.minecraft.blockentity.RenderStateComponent;
```

`MachineComponent` — public-класс с public-конструктором без аргументов и
`@EcsComponent(id = "yourmod:machine")`. Подключите processor, как для остальных
компонентов. SDMAL обнаружит сервис при общем setup; ручной регистрации нет.
`require(...)` получает уже зарегистрированный тип для сохранения и восстановления.
Каждый компонент оболочки нужно присоединять с зарегистрированным типом,
включая компоненты без сохраняемых полей.

`createComponents` вызывается только для нового блока. При чтении существующих
данных состав восстанавливается из сохранения. Для добавления компонентов
существующим блокам нужна явная миграция. `onEcsReady(WorldObject)` вызывается
после загрузки, повторной установки и замены клиентской композиции: здесь можно
перестроить render state и обновить кэш ссылок на компоненты.

Инициализаторы новых компонентов выполняются до регистрации объекта в менеджере.
Обычный `onAttached` при создании также может видеть объект без менеджера.
Доступ к миру и готовой оболочке размещайте в `onEcsReady`, `capture` или `apply`.
При восстановлении runtime-привязка устанавливается до `onAttached`.

## Тики и взаимодействие с миром

`SnapshotTickableComponent<S,R>` автоматически попадает в планировщик мира.
`capture` и `apply` работают в потоке мира; `compute` может выполняться на worker.
В worker передавайте только независимые данные. Живые компоненты, оболочка и
`Level` не должны попадать в snapshot. Для мира используйте очередь:

```java
context.enqueue(TickPhase.POST, level -> {
    level.setBlock(position, newState, 3);
});
```

В `capture` позицию можно получить из автоматически добавленного
`BlockEntityComponent.position()`. Она immutable и выводится из координат
оболочки, поэтому не дублируется в сохранении. При изменении сохраняемых полей
в `apply` вызовите `BlockEntityComponent.setChanged()` — это пометит чанк на запись.

Проверка активности выполняется максимум один раз за тик для объекта и учитывает
загруженность чанка, simulation distance, world border и совпадение установленной
оболочки. Спящий объект и его `tickInterval` продолжают соблюдаться. Клиентская
копия не выполняет компонентную симуляцию. Собственные `EcsSystem`, которые
вручную обходят весь менеджер, должны сами учитывать активность и сторону мира.

`@EcsNoThreadSafe` запрещает worker compute; `@EcsNoTickSkip` и декларации
`tickAccess`/`tickAfter` работают как у обычных ECS-компонентов. Автоматические
системы не разрешают двум экземплярам обращаться к общему изменяемому состоянию
без отдельной декларации доступа и согласованного алгоритма.

## Chunk lifecycle и сохранение

Объект регистрируется в менеджере при назначении мира/установке оболочки.
`setRemoved` и общий Mixin на `LevelChunk.clearAllBlockEntities` сохраняют его
состояние в оболочке и удаляют точное членство. Отложенные результаты и команды
старого экземпляра отбрасываются. Повторная установка создаёт новые компоненты,
сохраняя UUID и оставшуюся задержку тика. Ссылки на старые компоненты удерживать
нельзя.

Единственный владелец дисковых данных — BlockEntity/чанк, ключ `sdmal_ecs`.
Мировой ECS-архив не включает этот объект; `setPersistent(true)` для него запрещён.
Миграции, версии компонентов и сохранение неизвестных полей используют обычный
ECS-архив. Если тип отсутствует, данные повреждены или версия новее поддерживаемой,
объект остаётся неактивным, исходный payload сохраняется без замены значениями
по умолчанию. Диагностика доступна через `ecsRestoreFailure()`.

Жизненным циклом управляет оболочка. Не удаляйте hosted-объект напрямую из
менеджера и не закрывайте менеджер до сохранения/выгрузки чанков. Ошибки
сериализации выдаются явно; последний успешный snapshot не заменяется частичным.

## Синхронизация и рендер

`BlockEntityComponent.requestClientUpdate()` запрашивает обычное обновление
BlockEntity. Оболочка отправляет полный состав и только поля `@EcsSync`
либо tracked-поля `SyncedWorldObjectComponent`. Поля только с `@EcsPersist`
остаются серверными. Статические компоненты без сетевых полей создаются
через их фабрики. Для всех отправляемых типов нужна реализация на клиенте.

Компоненты кодируются напрямую через `FriendlyByteBuf`. Vanilla требует NBT
для update tag, поэтому её пакет оборачивает готовый двоичный frame в byte array;
дисковая ECS-NBT сериализация в этом пути не используется. Собственный транспорт
может вызвать `writeClientSnapshot(buf)` на сервере и `readClientSnapshot(buf)`
в потоке клиентского мира. Он должен обеспечить правильную позицию/тип блока,
порядок доставки, сторону и проверку получателя. Протокол передаёт UUID, типы и
номера прикрепления; встроенного номера сетевой ревизии у полного frame нет.
Предельный frame — 8 MiB. Отправляйте обновления пакетно, а не каждый тик.

Клиентский snapshot сначала полностью декодируется и проверяется, затем заменяет
всю композицию. Неверный формат/неизвестный тип не меняет прежнюю композицию и
откатывает reader index. Ошибки lifecycle после успешного decode сообщаются уже
после commit; побочные действия пользовательских callbacks не откатываются.

`RenderStateComponent<S>` публикует через volatile-ссылку полностью immutable
значение. Renderer читает `state()`, которое может быть `null` до публикации.
Создавайте state в `apply` и в `onEcsReady` после disk/network load. Сам mutable
компонент не должен служить входом renderer. Регистрация `BlockEntityRenderer`
остаётся у клиентского модуля мода, где доступны API нужной версии Minecraft.

## Компилируемый пример

В моде зарегистрированы блок, предмет и `BlockEntityType` с идентификатором
`sdmal:ecs_generator` на Fabric, Forge и NeoForge. Найдите «ECS-генератор»
во вкладке SDMAL или выполните:

```mcfunction
/give @s sdmal:ecs_generator
```

Поставьте блок и нажмите ПКМ (можно с предметом в руке). В чат придут текущая
серверная энергия, ёмкость и номер тика ECS. Генератор накапливает 1 единицу
за активный тик, максимум 1000: при 20 TPS он заполняется примерно за 50 секунд.
Остановка вне simulation distance и сохранение/перезагрузка мира сохраняют
обычный контракт hosted ECS. При разрушении выпадает новый пустой генератор;
энергия не переносится в предмет. Для повторного наблюдения роста поставьте
новый блок. Специальный BlockEntityRenderer для этой проверки не требуется:
оболочка рисуется как обычный куб с железными боками и красной верхней гранью.

`core/src/main/java/dev/sixik/sdmal/example/ecs/blockentity/`:

- `GeneratorBlock` — обычный Block + EntityBlock, наследует null ticker.
- `GeneratorBlockEntity` — добавляет генератор и обновляет render projection.
- `GeneratorContent` — общие свойства блока/предмета и стабильный registry path.
- `BlockGeneratorComponent` — `@EcsPersist` + `@EcsSync`, snapshot-вычисление,
  отметка чанка на сохранение и обновление клиента раз в 20 тиков/при заполнении.

Регистрируйте `GeneratorBlock(properties, () -> GENERATOR_TYPE)` как свой блок,
затем свой `BlockEntityType` с фабрикой
`(pos, state) -> new GeneratorBlockEntity(GENERATOR_TYPE, pos, state)`
и этим блоком в допустимом наборе. Для Forge/NeoForge передавайте getter registry
holder, для Fabric — supplier поля зарегистрированного типа. Используйте штатный
builder/constructor и registry properties вашей версии Minecraft. Точки входа
библиотеки содержат готовый пример регистрации; отдельный renderer для него
не регистрируется.

Вычисление примера намеренно простое: `Integer` snapshots и создание immutable
render state показывают контракт, а не заявляют отсутствие аллокаций. Тяжёлые
машины могут переиспользовать собственный primitive snapshot после барьера.

## Проверки и измерения

```powershell
.\gradlew.bat buildAll
.\gradlew.bat :core:1.20.1:jmhBlockEntities
```

JUnit проверяет host save/unload/reload, UUID, отсутствие дублирования в мировом
архиве, quarantine, runtime binding, сеть, ошибки callbacks, активацию,
сервисы и render publication. ASM-проверки сравнивают lifecycle/Mixin/network
сигнатуры с настоящим Minecraft bytecode каждой из пяти версий. Они не заменяют
игровую проверку chunk lifecycle и визуального renderer.

JMH сравнивает стоимость полного ECS-тика для обычных объектов, всех активных
hosted-объектов и 50% активных hosted-объектов. Это синтетический activity gate;
стоимость Minecraft lookup, реальных машин, сети и диска измерением не покрыта.
Результаты и условия запуска: [отчёт JMH](performance/block-entities/RESULTS.md).
