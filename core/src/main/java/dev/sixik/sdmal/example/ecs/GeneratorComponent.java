package dev.sixik.sdmal.example.ecs;

import dev.sixik.sdmal.api.ecs.Component;
import dev.sixik.sdmal.api.ecs.SnapshotTickableComponent;
import dev.sixik.sdmal.api.ecs.TickPhase;
import dev.sixik.sdmal.api.ecs.EcsContext;
import dev.sixik.sdmal.api.ecs.EcsObjectManager;
import dev.sixik.sdmal.api.ecs.annotations.EcsComponent;
import dev.sixik.sdmal.api.ecs.annotations.EcsData;
import dev.sixik.sdmal.api.ecs.annotations.EcsPersist;
import dev.sixik.sdmal.api.ecs.annotations.EcsSync;

import java.util.UUID;

/**
 * Пример автоматического NBT и прямой сетевой сериализации обычных private-полей. Изменяйте
 * компонент на потоке его менеджера; работа из фонового потока передаётся через execute.
 */
@EcsComponent(id = "sdmal_example:generator")
public final class GeneratorComponent extends Component
        implements SnapshotTickableComponent<GeneratorComponent.Input, GeneratorComponent.Battery> {
    /**
     * Заряд в условных единицах; сохраняется и передаётся по сети без ручного Codec.
     */
    @EcsPersist("battery")
    @EcsSync
    private Battery battery = new Battery(0, 100);

    /**
     * UUID владельца, либо null; сохраняется только на сервере, без передачи в снимке.
     */
    @EcsPersist("owner")
    private UUID ownerId;

    /**
     * Активность генератора; передаётся только по сети, при загрузке остаётся значением по
     * умолчанию.
     */
    @EcsSync("running")
    private boolean running = true;

    /**
     * Число применённых тиков с момента создания экземпляра; не сохраняется и не передаётся.
     */
    private int localTicks;

    /**
     * Создаёт компонент с пустой батареей ёмкостью 100 единиц и включённой генерацией.
     */
    public GeneratorComponent() {}

    /**
     * Копирует входные значения на потоке мира. Battery неизменяем, поэтому его можно передать в
     * worker.
     *
     * @param context контекст текущего тика
     * @return независимый снимок заряда и активности
     */
    @Override
    public Input capture(EcsContext context) {
        checkOwnerThread();
        return new Input(battery, running);
    }

    /**
     * Считает следующий заряд без обращения к полям компонента или живому миру.
     *
     * @param input копия входных значений
     * @param context очередь команд мира; не используется чистым вычислением
     * @return новое неизменяемое состояние батареи
     */
    @Override
    public Battery compute(Input input, EcsContext context) {
        Battery previous = input.battery();
        return input.running() && previous.stored() < previous.capacity()
                ? new Battery(previous.stored() + 1, previous.capacity())
                : previous;
    }

    /**
     * Применяет вычисленный заряд на потоке мира, после завершения всех jobs этого типа.
     *
     * @param result вычисленное состояние батареи
     * @param context контекст применения и отложенных команд мира
     */
    @Override
    public void apply(Battery result, EcsContext context) {
        checkOwnerThread();
        battery = result;
        localTicks++;
    }

    /**
     * @return POST, чтобы пример сохранял прежнее обновление после обычных тиков мира
     */
    @Override
    public TickPhase tickPhase() {
        return TickPhase.POST;
    }

    /**
     * Вход worker: только неизменяемые данные, без ссылки на компонент, EcsObject или Level.
     *
     * @param battery состояние батареи до тика
     * @param running активность генератора
     */
    public record Input(Battery battery, boolean running) {}

    /**
     * @return неизменяемый текущий заряд; читайте на потоке менеджера
     */
    public Battery battery() {
        return battery;
    }

    /**
     * @return UUID владельца либо null; читайте на потоке менеджера
     */
    public UUID ownerId() {
        return ownerId;
    }

    /**
     * @return true, если генерация включена; читайте на потоке менеджера
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * @return число применённых тиков; читайте на потоке менеджера
     */
    public int localTicks() {
        return localTicks;
    }

    /**
     * Устанавливает сохраняемую серверную принадлежность.
     *
     * @param ownerId UUID владельца либо null
     * @throws IllegalStateException если вызов пришёл с чужого потока менеджера
     */
    public void setOwnerId(UUID ownerId) {
        checkOwnerThread();
        this.ownerId = ownerId;
    }

    /**
     * Переключает генерацию; следующее writeNetwork автоматически прочитает это обычное поле.
     *
     * @param running true для генерации, false для остановки
     * @throws IllegalStateException если вызов пришёл с чужого потока менеджера
     */
    public void setRunning(boolean running) {
        checkOwnerThread();
        this.running = running;
    }

    /**
     * Свой тип данных, которому не требуется адаптер: поля record обрабатываются автоматически. В
     * сетевом снимке всего record используются те же стабильные ключи, что у NBT.
     *
     * @param stored количество заряда в диапазоне [0, capacity]
     * @param capacity максимальный заряд, неотрицательный
     */
    @EcsData
    public record Battery(@EcsPersist("stored") int stored, @EcsPersist("capacity") int capacity) {
        /**
         * Проверяет состояние при создании, NBT-загрузке и чтении сетевого снимка.
         *
         * @param stored количество заряда в диапазоне [0, capacity]
         * @param capacity максимальный заряд, неотрицательный
         * @throws IllegalArgumentException если заряд или ёмкость отрицательны либо заряд превышает
         *     ёмкость
         */
        public Battery {
            if (stored < 0 || capacity < stored)
                throw new IllegalArgumentException("Invalid battery charge");
        }
    }
}
