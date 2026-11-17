# Регистрация контента аннотациями

`@RegisterContent` автоматически регистрирует публичные классы, наследующие
`Item`, `Block`, `BlockEntity`, и классы, реализующие `ContentTab`.
Processor записывает их имена в `META-INF/sdmal/content`; библиотека обнаруживает
все индексы модов и подключает регистрацию к Fabric/Forge/NeoForge. Сгенерированные
Java-классы и ручной вызов регистрации не нужны.

## Предмет и блок

```java
import dev.sixik.sdmal.api.content.annotations.RegisterContent;
import net.minecraft.world.item.Item;

@RegisterContent(id = "mymod:token", tab = "mymod:main", ordinal = 0)
public final class TokenItem extends Item {
    public TokenItem(Properties properties) {
        super(properties.stacksTo(16));
    }
}
```

```java
import net.minecraft.world.level.block.Block;

@RegisterContent(id = "mymod:decorative_block", tab = "mymod:main", ordinal = 1)
public final class DecorativeBlock extends Block {
    public DecorativeBlock(Properties properties) {
        super(properties.strength(3.0f));
    }
}
```

Блок автоматически получает `BlockItem` с тем же id. Если нужен собственный
предмет блока, аннотируйте подкласс `BlockItem` с тем же id и публичным
конструктором `(Block, Item.Properties)`.

`id` должен быть namespaced и lowercase. Если `tab` пустой или не указан,
автоматического добавления предмета во вкладки нет. Если вкладка указана, но её
класс не объявлен, создаётся стандартная вкладка: название — перевод
`itemGroup.namespace.path`, иконка — первый предмет по порядку.

Порядок автоматических предметов во вкладке: сначала `ordinal` по возрастанию,
затем полный `id` при равном значении. Поддерживаются отрицательные значения.
Порядок исходников, загрузчиков и индексов модов на результат не влияет.

## BlockEntity и стандартный блок

```java
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

@RegisterContent(id = "mymod:machine", tab = "mymod:main", ordinal = 2)
public final class MachineEntity extends BlockEntity {
    public MachineEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
        super(type, position, state);
    }
}
```

Будут созданы три записи с id `mymod:machine`: блок, его `BlockItem` и
`BlockEntityType<MachineEntity>`. Тип передаётся в конструктор автоматически.
Стандартный блок — `RegisteredEntityBlock`: обычный модельный рендер, базовая
прочность 1.5, фабрика этой BlockEntity и отсутствие vanilla ticker.

Свою логику блока можно добавить отдельным классом с **тем же id**:

```java
import dev.sixik.sdmal.api.content.RegisteredEntityBlock;

@RegisterContent(id = "mymod:machine")
public final class MachineBlock extends RegisteredEntityBlock {
    public MachineBlock(Properties properties) {
        super(properties.strength(5.0f));
    }
}
```

Этот класс заменит стандартный блок, сохранив автоматические фабрику BlockEntity
и BlockItem. Повторных записей блока и предмета не будет.
Можно использовать другой подкласс `Block`, реализующий `EntityBlock`, и задать
собственную фабрику/тикер стандартными средствами Minecraft.
Обычный `Block` без `EntityBlock` при совпадающем id отклоняется.
`RegisteredEntityBlock` также требует аннотированную BlockEntity с тем же id;
отсутствующая пара обнаруживается при старте, до установки блока в мире.

Если у собственного блока `tab` не указан, используются `tab` и `ordinal` сущности.
Если оба класса явно указывают вкладку, их `tab` и `ordinal` должны совпадать.
Явно объявленный предмет с тем же id задаёт окончательные `tab`/`ordinal`, включая
пустой `tab`, позволяющий скрыть предмет из вкладок.

Для ECS вместо `BlockEntity` наследуйте `EcsBlockEntity` и реализуйте
`createComponents(WorldObject)`. Игровой пример `AnnotatedMachineEntity` именно
так подключает существующий компонент генератора. ECS продолжает тикать через мир;
регистрация не добавляет второй ticker. Обычные BlockEntity с vanilla-логикой
получают ticker только из собственного блока.

## Своя вкладка

`CreativeModeTab` создаётся разными API в 1.19 и новых Minecraft. Чтобы один класс
работал на всех версиях, аннотируйте реализацию `ContentTab`:

```java
import dev.sixik.sdmal.api.content.ContentTab;
import dev.sixik.sdmal.api.content.RegisteredContent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

@RegisterContent(id = "mymod:main")
public final class MainTab implements ContentTab {
    @Override
    public Component title() {
        return Component.literal("My machines");
    }

    @Override
    public ItemStack icon() {
        return RegisteredContent.item(TokenItem.class).getDefaultInstance();
    }
}
```

Необязательные методы:

- `title()` — заголовок; `null` оставляет стандартный перевод;
- `icon()` — иконка; `ItemStack.EMPTY` выбирает первый автоматический предмет,
  а для пустой вкладки — сундук;
- `appendItems(Consumer<ItemStack>)` — дополнительные предметы после упорядоченного
  автоматического содержимого; не добавляйте один и тот же стэк повторно.

Предметы с соответствующим `tab` попадут в эту вкладку автоматически. Не требуется
самостоятельно перечислять их в `appendItems`. Настройка названия/иконки и добавление
дополнительных элементов поддерживаются общим API; subclassing нативного
`CreativeModeTab` и произвольная настройка его версионного builder в этот контракт
не входят.

Можно ссылаться на существующую vanilla-вкладку: например,
`tab = "minecraft:building_blocks"`. Автоматические предметы добавляются в её конец
с сортировкой по `ordinal`/`id`; существующий контент не пересортировывается.
Идентификаторы остальных vanilla-вкладок зависят от версии: в 1.19.2 используются
`minecraft:decorations`, `minecraft:redstone`, `minecraft:transportation`,
`minecraft:misc`, `minecraft:food`, `minecraft:tools`, `minecraft:combat`,
`minecraft:brewing`, `minecraft:materials` и служебные `search`/`inventory`.
Для неизвестного id с namespace `minecraft` выдаётся ошибка вместо новой вкладки.

Новые модовые id вкладок из `tab` считаются принадлежащими автоматической
регистрации, если вкладка ещё не существует при обнаружении. Для модовой вкладки,
которую тоже регистрируете аннотацией, используйте тот же id. Не смешивайте такую
декларацию с отложенной ручной регистрацией того же id другим механизмом.

## Конструкторы и доступ к экземплярам

| Тип | Публичный конструктор |
| --- | --- |
| `Item` | `(Item.Properties)` |
| `Block` | `(BlockBehaviour.Properties)` |
| `BlockItem` | `(Block, Item.Properties)` |
| `BlockEntity` | `(BlockEntityType<?>, BlockPos, BlockState)` |
| `BlockEntity`, альтернативно | `(BlockPos, BlockState)`; тип разрешается самим классом |
| `ContentTab` | без аргументов |

Внедрённые свойства уже содержат ключ регистрации в версиях, где он обязателен.
Изменяйте переданные properties вместо создания нового экземпляра без `setId`.
Поэтому noarg-конструкторы предметов/блоков не используются.
Классы должны быть публичными, конкретными, без незаданных generic-параметров;
вложенные классы — `public static`, с публичными внешними классами.

После завершения соответствующей фазы регистрации:

```java
RegisteredContent.item(TokenItem.class);
RegisteredContent.block(MachineBlock.class);
RegisteredContent.block("mymod:machine");
RegisteredContent.item("mymod:machine");
RegisteredContent.blockEntityType(MachineEntity.class);
RegisteredContent.tab("mymod:main");
```

До регистрации lookup выдаёт понятную ошибку жизненного цикла. В конструкторах
контента не обращайтесь к другим ещё не зарегистрированным экземплярам; используйте
отложенные lookup из игровых методов и из `icon()`. Для `BlockEntity` предпочтителен
конструктор с внедрённым типом. В 26.x создание item stacks/чтение их компонентов
также требует завершения нативной фазы привязки компонентов — одного обнаружения
класса или создания `Item` для этого недостаточно.

## Подключение и пример в игре

В этом проекте processor уже подключён. В стороннем моде подключите SDMAL обычным
способом, а JAR `sdmal-ecs-processor` — как `annotationProcessor` в каждом модуле,
компилирующем аннотированные классы. В этом JAR теперь три processor: ECS, пакеты
и контент. Он не нужен в папке `mods`. При объединении JAR сохраняйте и объединяйте
строки `META-INF/sdmal/content`; сами классы должны присутствовать в общем игровом
source set, включая dedicated server.

В `example/content` есть полностью зарегистрированный набор:

1. `sdmal:annotated_token` — предмет, `ordinal = -1`;
2. `sdmal:annotated_block` — обычный блок с автоматическим предметом, `ordinal = 0`;
3. `sdmal:annotated_machine` — ECS BlockEntity с отдельным классом блока, `ordinal = 1`;
4. `sdmal:annotated_default_machine` — BlockEntity со стандартным блоком, `ordinal = 2`.

Они находятся во вкладке **SDMAL: Автоматический контент**.
`sdmal:annotated_hidden_item` доступен через `/give`, но не добавляется во вкладки.
Для примеров добавлены модели со стандартными текстурами Minecraft и локализации.

Аннотация регистрирует игровые объекты. Для собственного контента модели,
blockstates, локализации, loot tables, recipes и BlockEntityRenderer добавляются
обычными средствами Minecraft. Существующие native id не перезаписываются:
допускается только объединение разных категорий одного автоматического id,
например Block + BlockEntity + BlockItem.
