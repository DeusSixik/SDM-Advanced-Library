package dev.sixik.sdmal.example.ecs;

import dev.sixik.sdmal.api.ecs.WorldObject;
import dev.sixik.sdmal.api.ecs.WorldObjectComponentRegistry;
import dev.sixik.sdmal.api.ecs.WorldObjectComponentType;
import dev.sixik.sdmal.api.ecs.WorldObjectManager;
import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.ecs.minecraft.LevelObjectManager;

import net.minecraft.server.level.ServerLevel;

/**
 * Utility showing how to consume automatically discovered ECS components.
 */
public final class EcsExample {
    /**
     * Запрещает создание экземпляра класса регистрации.
     */
    private EcsExample() {}

    /**
     * Returns the descriptor discovered from {@link EcsComponent} during common setup.
     *
     * @return automatically registered generator type
     */
    public static WorldObjectComponentType<GeneratorComponent> generatorType() {
        return WorldObjectComponentRegistry.instance().require(GeneratorComponent.class);
    }

    /**
     * Создаёт сохраняемый объект: первый ближайший тик, затем один вызов генератора раз в 20 тиков
     * мира. Не вызывайте при каждой загрузке мира: ранее сохранённые объекты восстанавливаются
     * автоматически.
     *
     * @param level серверный мир; вызывайте на его потоке при игровом событии создания генератора
     * @return зарегистрированный объект с одним GeneratorComponent
     * @throws IllegalStateException если тип не зарегистрирован, менеджер закрыт или поток неверен
     */
    public static WorldObject createGenerator(ServerLevel level) {
        return createGenerator(LevelObjectManager.getManager(level));
    }

    /**
     * Создаёт тот же пример в отдельном менеджере для демонстрации без запуска Minecraft-мира.
     * Такой менеджер тикает, сохраняет и закрывает вызывающий код.
     *
     * @param manager живой менеджер; вызов на потоке его владельца
     * @return новый сохраняемый объект с типизированным компонентом
     * @throws IllegalStateException если тип не зарегистрирован, менеджер закрыт или поток неверен
     */
    public static WorldObject createGenerator(WorldObjectManager manager) {
        var generatorType = generatorType();
        WorldObject object = manager.create();
        object.setTickInterval(20);
        object.attach(new GeneratorComponent(), generatorType);
        return object;
    }
}
