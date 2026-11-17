package dev.sixik.sdmal.example.ecs;

import dev.sixik.sdmal.core.ecs.AnnotatedWorldObjectComponent;
import dev.sixik.sdmal.core.ecs.EcsComponent;
import dev.sixik.sdmal.core.ecs.TickableComponent;
import dev.sixik.sdmal.core.ecs.WorldObjectManager;
import dev.sixik.sdmal.core.ecs.serialization.EcsData;
import dev.sixik.sdmal.core.ecs.serialization.EcsPersist;
import dev.sixik.sdmal.core.ecs.serialization.EcsSync;

import java.util.UUID;

/**
 * Пример автоматического NBT и прямой сетевой сериализации обычных private-полей.
 * Изменяйте компонент на потоке его менеджера; работа из фонового потока передаётся через execute.
 */
@EcsComponent(id = "sdmal_example:generator")
public final class GeneratorComponent extends AnnotatedWorldObjectComponent implements TickableComponent {
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
     * Активность генератора; передаётся только по сети, при загрузке остаётся значением по умолчанию.
     */
    @EcsSync("running")
    private boolean running = true;

    /**
     * Число вызовов onTick с момента создания экземпляра; не сохраняется и не передаётся.
     */
    private int localTicks;

    /**
     * Создаёт компонент с пустой батареей ёмкостью 100 единиц и включённой генерацией.
     */
    public GeneratorComponent() {
    }

    /**
     * Добавляет одну единицу заряда за вызов; частоту задаёт tickInterval объекта.
     *
     * @param tick локальный номер тика менеджера, не время суток Minecraft
     * @throws IllegalStateException если управляемый компонент вызван с чужого потока
     */
    @Override
    public void onTick(long tick) {
        checkWorldThread();
        localTicks++;
        if (running && battery.stored() < battery.capacity()) {
            battery = new Battery(battery.stored() + 1, battery.capacity());
        }
    }

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
     * @return число локальных вызовов onTick; читайте на потоке менеджера
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
        checkWorldThread();
        this.ownerId = ownerId;
    }

    /**
     * Переключает генерацию; следующее writeNetwork автоматически прочитает это обычное поле.
     *
     * @param running true для генерации, false для остановки
     * @throws IllegalStateException если вызов пришёл с чужого потока менеджера
     */
    public void setRunning(boolean running) {
        checkWorldThread();
        this.running = running;
    }

    /**
     * Проверяет поток прикреплённого менеджера; standalone-экземпляр координирует вызывающий код.
     *
     * @throws IllegalStateException если текущий поток не принадлежит менеджеру
     */
    private void checkWorldThread() {
        owner().flatMap(object -> object.manager()).ifPresent(WorldObjectManager::checkThread);
    }

    /**
     * Свой тип данных, которому не требуется адаптер: поля record обрабатываются автоматически.
     * В сетевом снимке всего record используются те же стабильные ключи, что у NBT.
     *
     * @param stored   количество заряда в диапазоне [0, capacity]
     * @param capacity максимальный заряд, неотрицательный
     */
    @EcsData
    public record Battery(@EcsPersist("stored") int stored, @EcsPersist("capacity") int capacity) {
        /**
         * Проверяет состояние при создании, NBT-загрузке и чтении сетевого снимка.
         *
         * @param stored   количество заряда в диапазоне [0, capacity]
         * @param capacity максимальный заряд, неотрицательный
         * @throws IllegalArgumentException если заряд или ёмкость отрицательны либо заряд превышает ёмкость
         */
        public Battery {
            if (stored < 0 || capacity < stored) throw new IllegalArgumentException("Invalid battery charge");
        }
    }
}
