# ECS: небольшой пример

Начните с исходников [GeneratorComponent.java](src/main/java/dev/sixik/sdmal/example/ecs/GeneratorComponent.java) и [EcsExample.java](src/main/java/dev/sixik/sdmal/example/ecs/EcsExample.java). Это компилируемые примеры, которые библиотека сама не запускает. Их можно скопировать в свой мод и заменить namespace типа.

| Поле генератора | NBT | FriendlyByteBuf |
| --- | --- | --- |
| `battery` — record с `@EcsData` | `@EcsPersist` | `@EcsSync` |
| `ownerId` | `@EcsPersist` | — |
| `running` | — | `@EcsSync("running")` |
| `localTicks` | — | — |

У record есть `@EcsPersist` на каждом параметре. Эти ключи используются и при сохранении, и при прямой двоичной передаче record. Ручного Codec здесь нет.

## Создание в мире

Компонент уже помечен `@EcsComponent`. При подключённом `sdmal-ecs-processor`
сборка добавит его в services, а SDMAL зарегистрирует его автоматически до загрузки
миров. Отдельного вызова регистрации в вашем моде не требуется.

```java
// Игровое событие создания генератора, на потоке серверного мира:
WorldObject object = EcsExample.createGenerator(serverLevel);
GeneratorComponent generator = object.getSingle(EcsExample.generatorType()).orElseThrow();
generator.setOwnerId(player.getUUID());
```

Менеджер мира автоматически тикает объект и сохраняет его при сохранении мира. Пример генерирует по единице заряда раз в 20 тиков; при нормальных 20 TPS это примерно одна секунда. При `/tick freeze` менеджер также останавливается там, где Minecraft поддерживает эту команду. Не вызывайте `tickAll()` вручную для менеджера обычного мира и не создавайте объект заново при каждой загрузке: его UUID и компоненты уже восстанавливаются из архива.

## Сохранение без игрового мира

Этот фрагмент показывает сам механизм архива; при обычной интеграции с `ServerLevel` файловые операции выполняются автоматически.

```java
// Только standalone-запуск без общего setup SDMAL:
WorldObjectComponentRegistry.instance().registerServices();
try (var source = new WorldObjectManager(); var restored = new WorldObjectManager()) {
    var object = EcsExample.createGenerator(source);
    source.tickAll(); // Вручную тикаем только standalone-менеджер.

    CompoundTag archive = source.writeNbt();
    var warnings = restored.readNbt(archive); // Только свежий пустой менеджер.
    var copy = restored.get(object.id()).orElseThrow()
        .getSingle(EcsExample.generatorType()).orElseThrow();

    // copy.battery().stored() == 1; copy.localTicks() == 0.
}
```

NBT включает только `battery` и `ownerId`. Новые экземпляры получают исходные значения `running` и `localTicks`. При изменении формата сохранения используйте `dataVersion()` и `migrateNbt()`.

## Полный сетевой снимок

На отправителе, на потоке менеджера:

```java
byte[] snapshot;
FriendlyByteBuf output = new FriendlyByteBuf(Unpooled.buffer());
try {
    generator.writeNetwork(output);
    snapshot = new byte[output.readableBytes()];
    output.readBytes(snapshot); // Независимые байты переживут release буфера.
} finally {
    output.release();
}
// Передайте snapshot и object.id() своим Fabric/Forge/NeoForge packet API.
```

У получателя сетевой код находит или создаёт клиентский mirror-объект с тем же UUID и компонентом. Если он нужен только для отображения, пометьте mirror `setPersistent(false)` и вызовите `sleep()`, чтобы он не генерировал заряд сам. Перенесите независимые байты снимка на поток клиентского мира:

```java
client.execute(() -> {
    FriendlyByteBuf input = new FriendlyByteBuf(Unpooled.wrappedBuffer(snapshot));
    try {
        clientGenerator.readNetwork(input);
    } finally {
        input.release();
    }
});
```

`battery` и `running` применятся к mirror-компоненту, `ownerId` и `localTicks` останутся локальными. NBT-теги при этой передаче не создаются. Это полный снимок с проверкой схемы; доставка, выбор получателей и порядок пакетов задаются модом.

Импорты для фрагментов: `dev.sixik.sdmal.example.ecs.*`, `dev.sixik.sdmal.core.ecs.*`, `net.minecraft.nbt.CompoundTag`, `net.minecraft.network.FriendlyByteBuf`, `io.netty.buffer.Unpooled`.

Сторонние типы, адаптеры и миграции: [ECS-ANNOTATIONS.md](ECS-ANNOTATIONS.md).
