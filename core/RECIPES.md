# Recipes: поиск из нескольких потоков

API находится в `dev.sixik.sdmal.api.recipes`, интеграция с Minecraft — в
`dev.sixik.sdmal.api.recipes.minecraft`. Модуль уже входит в `core` и итоговые
JAR загрузчиков; дополнительных зависимостей для него нет.

`RecipeManager` публикует целое неизменяемое поколение рецептов. Поиск не берёт
блокировок. Перезагрузка строит новые индексы отдельно, затем заменяет одну
`volatile`-ссылку. Уже запущенные задачи продолжают читать своё старое поколение.
Неудачная сборка не публикует частично обновлённые данные.

## Собственные рецепты

```java
public record PressInput(int material, int count) {}
public record PressRecipe(int material, int requiredCount, int output, int ticks) {}

public static final RecipeType<PressInput, PressRecipe> PRESSING = RecipeType.indexed(
        "mymod:pressing",
        (recipe, input) -> recipe.material() == input.material()
                && input.count() >= recipe.requiredCount(),
        PressInput::material,
        recipe -> new int[] {recipe.material()});

RecipeManager recipes = new RecipeManager();
recipes.replaceAll(ObjectArrayView.of(
        new RecipeEntry<>("mymod:iron_plate", PRESSING,
                new PressRecipe(1, 2, 101, 40))));

// Захватить перед отправкой ECS-задач.
RecipeSnapshot snapshot = recipes.snapshot();
PressInput input = new PressInput(1, 8);

// Worker: результатом будет существующая запись или null.
RecipeEntry<PressRecipe> match = snapshot.first(PRESSING, input);
```

На категорию нужен один общий `RecipeType`, обычно `static final`. Два разных
токена с одинаковым ID в одном поколении запрещены. ID рецепта уникален во всём
менеджере. Порядок поиска детерминированный: по возрастанию полного ID рецепта.
Он может отличаться от приоритетов, которые моды задают в Minecraft.

`RecipeType.of(id, matcher)` подходит для категории без индекса. В `indexed`
ключ только сокращает список кандидатов: каждый кандидат дополнительно проходит
`matcher`. **Каждый подходящий рецепт обязан объявлять ключ входа** в `recipeKeys`.
Пустой массив ключей исключает рецепт из поиска. Массивы ключей читаются только
при построении поколения; на запрос они не создаются.

Для серии запросов одного типа можно сохранить `snapshot.lookup(PRESSING)`.
Если категория отсутствует, `lookup` возвращает `null`. У `RecipeLookup` есть:

- `first(input)` — первая запись или `null`, без создания списка результатов;
- `findAll(input, output)` — дописать совпадения в буфер вызывающего потока;
- `forEachMatch(input, consumer)` — обработать совпадения на вызывающем потоке;
- `entries()` и `size()` — неизменяемое представление категории и её размер.

Полный снимок предоставляет `entries()`, `byId(id)`, `generation()` и `size()`.
Удержание старых снимков сохраняет и их данные в памяти: освобождайте ссылки,
когда задачи завершились.

### Контракт потокобезопасности

Значения `RecipeEntry.value()` должны быть глубоко неизменяемыми или явно
поддерживать конкурентное чтение. `record` с изменяемым массивом/списком сам по
себе этого не гарантирует. Менеджер копирует свои коллекции и индексы, но не может
автоматически заморозить произвольный объект пользователя.

`matcher` и функция ключа должны быть чистыми и допускать одновременные вызовы.
Вход и выходной буфер принадлежат конкретной задаче. Нельзя читать в matcher
живой `Level`, инвентарь, изменяемый `ItemStack`, теги реестра или native-рецепт.

`replaceAll` и `clear` сериализуют писателей. `isCurrent(snapshot)` проверяет
точную идентичность поколения, включая принадлежность менеджеру. Для standalone
менеджера это проверка состояния в момент вызова, а не транзакция с другим
потоком-писателем. Публикацию и применение к игровым объектам выполняйте на одном
потоке-владельце.

## Рецепты Minecraft и ECS

```java
// Capture: серверный поток, до отправки задачи.
ServerRecipes service = ServerRecipes.get(server);
RecipeSnapshot snapshot = service.snapshot();
SingleItemInput input = SingleItemInput.capture(machineInput);

// Compute: worker, без Level, инвентаря и native Ingredient.test().
RecipeEntry<CookingRecipe> match = snapshot.first(CookingRecipes.SMELTING, input);

// Apply: серверный поток.
if (match == null || !service.isCurrent(snapshot)) {
    return;
}
// Здесь повторно проверить текущий вход, свободное место и права машины.
ItemStack output = match.value().output().createStack(server);
// Только после этих проверок расходовать вход и добавлять выход.
```

Готовый ECS-компонент:
[`EcsRecipeSearchComponent`](src/main/java/dev/sixik/sdmal/example/recipes/EcsRecipeSearchComponent.java).
Он реализует `SnapshotTickableComponent`, поэтому автоматически участвует в ECS
без отдельной системы. `setInput` захватывает вход и меняет ревизию; `capture`
получает снимок; `compute` ищет рецепт; `apply` проверяет поколение и ревизию.
Это пример планирования: топливо, прогресс, расход предметов и выдачу результата
добавляет сама машина. Если завершение происходит позднее, поколение и инвентарь
нужно ещё раз проверить именно в момент расхода.

`ServerRecipes.get(server)` вызывается на серверном потоке. Полученные `service`,
`snapshot` и `lookup` можно передать worker. Чтение `snapshot()` и `coverage()`
безопасно из нескольких потоков. Подготовка native-данных, регистрация адаптеров,
`refresh()` и создание изменяемого выходного `ItemStack` выполняются на серверном
потоке; API отклоняет такие операции с worker.

Сервис отдельный для каждого сервера. Миксин подготавливает начальное поколение
до тиков мира, а успешный `/reload` включает публикацию поколения в возвращаемый
`CompletableFuture`. Старые снимки остаются читаемыми, но не проходят `isCurrent`.
При ошибке адаптера прежнее поколение остаётся доступным для чтения, а применение
запрещено до успешного `refresh()`. При остановке сервера применение также
отключается. Моды, меняющие рецепты или теги на месте вне стандартного reload,
должны явно вызвать `refresh()` после изменения.

### Встроенные адаптеры

Поддержаны точные vanilla-классы и обычные item/tag ingredients:

| Категория | Токен |
| --- | --- |
| Плавка | `CookingRecipes.SMELTING` |
| Плавильная печь | `CookingRecipes.BLASTING` |
| Коптильня | `CookingRecipes.SMOKING` |
| Костёр | `CookingRecipes.CAMPFIRE` |

Теги разворачиваются в числовые ID при подготовке. `SingleItemInput` содержит
только ID предмета и количество. `CookingRecipe` хранит допустимые ID, длительность,
опыт и замороженный результат. NBT/components выхода кодируются при подготовке
и независимо восстанавливаются в `FrozenItemStack.createStack(server)`.
Числовые ID действуют в пределах текущего сервера; их не следует сохранять на диск
или передавать между разными сессиями как постоянные идентификаторы.

Shaped, shapeless, smithing, special/dynamic-рецепты и произвольные модовые
ingredients автоматически не преобразуются. Их нельзя безопасно выполнять на
worker через обычный `Recipe.matches(input, level)`. Список неподдержанных ID
доступен в `service.coverage().unsupportedIds()` и соответствует последнему
успешно опубликованному поколению. Безопасность встроенного адаптера проверяется
по точному классу рецепта и отсутствию custom ingredient; подклассы не получают
vanilla-семантику автоматически.

### Адаптер своего Minecraft-рецепта

```java
ServerRecipes service = ServerRecipes.get(server);
service.registerAdapter(MyPressRecipe.class, (id, nativeRecipe, registries) -> {
    // Серверный поток: скопировать/нормализовать все необходимые данные.
    PressRecipe frozen = new PressRecipe(nativeRecipe.materialId(),
            nativeRecipe.requiredCount(), nativeRecipe.outputId(), nativeRecipe.ticks());
    return new RecipeEntry<>(id, PRESSING, frozen);
});
service.refresh();
```

`MyPressRecipe` — ваш класс, реализующий native `Recipe`. Адаптер привязан к
**точному классу**, возвращает тот же ID и собственную категорию с чистым matcher.
`null` означает, что конкретный рецепт не поддержан. Уже зарегистрированный
адаптер заменить нельзя. Не сохраняйте native-рецепт, его mutable-поля, `Level`
или ссылки на теги внутри подготовленного значения.

## Проверка и измерения

Самостоятельный запускаемый пример:
[`StandaloneRecipesExample`](src/main/java/dev/sixik/sdmal/example/recipes/StandaloneRecipesExample.java).

```powershell
.\gradlew.bat :core:1.20.1:recipesExample
.\gradlew.bat :core:1.20.1:test
.\gradlew.bat :core:1.20.1:jmhRecipes
```

JUnit проверяет конкурентное чтение при 500 заменах поколений, отсутствие
частичной публикации, индексы, ошибки адаптеров, замороженный output,
ограничение native-операций потоком-владельцем и отклонение устаревших ECS-результатов.

25 тестов модуля проходят на всех пяти версиях Minecraft. `buildAll` собирает
14 вариантов загрузчиков. Применение серверного миксина проверено при запуске
Fabric на всех пяти версиях и Forge 1.20.1. Это проверка загрузки классов, без
создания игрового мира и игрового `/reload`.

NeoForge 26.3 собирается, но его `--initSettings` завершается до создания
серверного потока, что загрузчик трактует как `Couldn't find Minecraft server
thread`. Этот режим не подтвердил применение миксина NeoForge; полноценный
игровой запуск на нём не проверялся.

JMH использует одинаковые immutable-данные, поиск последнего рецепта, четыре
читателя, размеры 256/4096, 5×1 с прогрева, 5×1 с измерения, один fork и `-prof gc`.
JSON сохраняется в `core/versions/1.20.1/build/reports/jmh/recipes.json`.
Замер относится к самому поиску; создание входного snapshot, перестроение
поколения, декодирование ItemStack и полный тик машины в него не входят.

Прогон 18.11.2026, Intel Core i7-14700KF, Azul JDK 17.0.20.1, G1, heap 512 МиБ:

| Вариант, 4 читателя | 256 рецептов, нс/операцию | 4096 рецептов, нс/операцию |
| --- | ---: | ---: |
| Общий lock + линейный поиск | 898,4 | 50 862,1 |
| Снимок + линейный поиск | 147,9 | 7 877,0 |
| Снимок + примитивный индекс | 2,16 | 2,16 |
| Менеджер + примитивный индекс | 3,88 | 3,87 |

GC profiler показал около нуля B/op у индексированных запросов (менее 0,00001
B/op, включая фоновые расходы измерителя). Это синтетический прогон с одним
кандидатом и повторяемым ключом; число кандидатов и стоимость пользовательского
matcher влияют на реальную скорость. У locked-поиска на 4096 рецептах широкий
99,9% интервал: ±44 782 нс/операцию; у линейного снимка ±1 765. По этим данным
нельзя обещать фиксированное ускорение для каждой машины или модпака.
