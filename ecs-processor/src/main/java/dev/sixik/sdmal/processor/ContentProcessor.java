package dev.sixik.sdmal.processor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/**
 * Validates typed content declarations and emits one deterministic resource index per compilation.
 */
@SupportedAnnotationTypes("*")
public final class ContentProcessor extends AbstractProcessor {
    /**
     * Runtime annotation name, resolved without a Minecraft dependency.
     */
    private static final String ANNOTATION =
            "dev.sixik.sdmal.api.content.annotations.RegisterContent";

    /**
     * Supported base types, in inference order.
     */
    private static final List<String> BASES =
            List.of(
                    "net.minecraft.world.item.Item",
                    "net.minecraft.world.level.block.Block",
                    "net.minecraft.world.level.block.entity.BlockEntity",
                    "dev.sixik.sdmal.api.content.ContentTab",
                    "net.minecraft.world.entity.Entity",
                    "net.minecraft.world.inventory.AbstractContainerMenu",
                    "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen",
                    "net.minecraft.client.renderer.entity.EntityRenderer",
                    "net.minecraft.client.gui.screens.Screen");

    /**
     * Sorted binary names and originating source elements.
     */
    private final Map<String, TypeElement> types = new TreeMap<>();

    /**
     * Client-only binary names kept out of the common/server discovery index.
     */
    private final Map<String, TypeElement> clientTypes = new TreeMap<>();

    /**
     * Declared registry category/id pairs, rejecting duplicates but allowing Block + BlockEntity.
     */
    private final Map<String, TypeElement> ids = new TreeMap<>();

    /**
     * Whether diagnostics prevent an incomplete output index.
     */
    private boolean invalid;

    /**
     * @return source level supported by the running compiler
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * Collects declarations without claiming annotations used by other processors.
     *
     * @param annotations annotations in the current round
     * @param round       compiler processing round
     * @return false to permit other processors
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        var annotation = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
        if (annotation == null) return false;
        if (round.processingOver()) {
            if (!invalid && !round.errorRaised()) {
                writeIndex(types, "META-INF/sdmal/content");
                writeIndex(clientTypes, "META-INF/sdmal/content-client");
            }
            return false;
        }
        for (var element : round.getElementsAnnotatedWith(annotation)) collect(element);
        return false;
    }

    /**
     * @param element annotated declaration
     */
    private void collect(Element element) {
        if (!(element instanceof TypeElement type)
                || element.getKind() != ElementKind.CLASS
                || type.getModifiers().contains(Modifier.ABSTRACT)) {
            error(element, "@RegisterContent requires a concrete class");
            return;
        }
        if (!type.getTypeParameters().isEmpty()) {
            error(type, "Content type parameters must be concrete");
            return;
        }
        for (Element enclosing = type;
             enclosing instanceof TypeElement;
             enclosing = enclosing.getEnclosingElement()) {
            if (!enclosing.getModifiers().contains(Modifier.PUBLIC)) {
                error(type, "Content and enclosing classes must be public");
                return;
            }
        }
        if (type.getNestingKind().isNested() && !type.getModifiers().contains(Modifier.STATIC)) {
            error(type, "Nested content must be static");
            return;
        }
        int kind = -1;
        for (int index = 0; index < BASES.size(); index++)
            if (inherits(type, BASES.get(index))) {
                kind = index;
                break;
            }
        if (kind < 0) {
            error(type, "Content must extend Item, Block, BlockEntity, Entity, AbstractContainerMenu,"
                    + " AbstractContainerScreen, EntityRenderer or implement ContentTab");
            return;
        }
        String id = value(type, "id");
        String tab = value(type, "tab");
        if (!identifier(id) || (!tab.isEmpty() && !identifier(tab))) {
            error(
                    type,
                    "Content id/tab must be lowercase namespaced identifiers of at most 256"
                            + " characters");
            return;
        }
        if (kind >= 3 && !tab.isEmpty()) {
            error(type, "ContentTab, Entity, menus and client declarations cannot belong to a creative tab");
            return;
        }
        var properties = "net.minecraft.world.item.Item.Properties";
        boolean validConstructor =
                switch (kind) {
                    case 0 -> inherits(type, "net.minecraft.world.item.BlockItem")
                            ? hasConstructor(
                            type,
                            "net.minecraft.world.level.block.Block",
                            properties)
                            : hasConstructor(type, properties);
                    case 1 -> hasConstructor(
                            type,
                            "net.minecraft.world.level.block.state.BlockBehaviour.Properties");
                    case 2 -> hasConstructor(
                            type,
                            "net.minecraft.world.level.block.entity.BlockEntityType",
                            "net.minecraft.core.BlockPos",
                            "net.minecraft.world.level.block.state.BlockState")
                            || hasConstructor(
                            type,
                            "net.minecraft.core.BlockPos",
                            "net.minecraft.world.level.block.state.BlockState");
                    case 3 -> hasConstructor(type);
                    case 4 -> hasConstructor(type, "net.minecraft.world.entity.EntityType", "net.minecraft.world.level.Level");
                    case 5 -> hasConstructor(type, "net.minecraft.world.inventory.MenuType", "int", "net.minecraft.world.entity.player.Inventory")
                            || hasConstructor(type, "int", "net.minecraft.world.entity.player.Inventory");
                    case 6 -> screenConstructor(type);
                    case 7 -> hasConstructor(type, "net.minecraft.client.renderer.entity.EntityRendererProvider.Context");
                    case 8 -> hasConstructor(type, "net.minecraft.network.chat.Component") || hasConstructor(type);
                    default -> false;
                };
        if (!validConstructor) {
            error(
                    type,
                    "Unsupported public content constructor: use Item.Properties,"
                            + " BlockBehaviour.Properties, (Block, Item.Properties) for BlockItem,"
                            + " (BlockEntityType, BlockPos, BlockState) for BlockEntity, or noargs for"
                            + " ContentTab; Entity(EntityType, Level), Menu(MenuType, int, Inventory)"
                            + " or Menu(int, Inventory), Screen(ConcreteMenu, Inventory, Component),"
                            + " EntityRenderer(EntityRendererProvider.Context), Screen(Component) or Screen()");
            return;
        }
        if (kind == 4 && inherits(type, "net.minecraft.world.entity.LivingEntity") && !hasAttributes(type)) {
            error(type, "Living content requires public static AttributeSupplier.Builder createAttributes()");
            return;
        }
        var previous = ids.putIfAbsent((kind == 8 ? 6 : kind) + ":" + id, type);
        if (previous != null && !previous.equals(type)) {
            error(
                    type,
                    "Duplicate content registry id "
                            + id
                            + " also declared by "
                            + previous.getQualifiedName());
            return;
        }
        var index = kind >= 6 ? clientTypes : types;
        index.put(processingEnv.getElementUtils().getBinaryName(type).toString(), type);
    }

    /**
     * Requires a constructor accepting the exact resolved menu generic argument.
     *
     * @param type concrete screen class
     * @return whether the declared screen can construct its own menu type
     */
    private boolean screenConstructor(TypeElement type) {
        var base = supertype(type.asType(), BASES.get(6));
        if (base == null || base.getTypeArguments().isEmpty()) {
            return false;
        }
        String menu = processingEnv.getTypeUtils().erasure(base.getTypeArguments().get(0)).toString();
        if (hasConstructor(type, menu, "net.minecraft.world.entity.player.Inventory", "net.minecraft.network.chat.Component")) {
            return true;
        }
        for (var constructor : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            var parameters = constructor.getParameters();
            if (!constructor.getModifiers().contains(Modifier.PUBLIC) || parameters.size() != 3) {
                continue;
            }
            if (processingEnv.getTypeUtils().isAssignable(parameters.get(0).asType(), base.getTypeArguments().get(0))
                    && processingEnv.getTypeUtils().erasure(parameters.get(1).asType()).toString()
                    .equals("net.minecraft.world.entity.player.Inventory")
                    && processingEnv.getTypeUtils().erasure(parameters.get(2).asType()).toString()
                    .equals("net.minecraft.network.chat.Component")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves generic base arguments through intermediate superclasses.
     *
     * @param type current substituted type
     * @param name requested raw base name
     * @return matching substituted declared base, or null
     */
    private DeclaredType supertype(TypeMirror type, String name) {
        if (type instanceof DeclaredType declared
                && ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(name)) {
            return declared;
        }
        for (var parent : processingEnv.getTypeUtils().directSupertypes(type)) {
            var found = supertype(parent, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Checks conventional living attributes factories, including inherited public static methods.
     *
     * @param type living entity declaration
     * @return whether a correctly typed public factory is available
     */
    private boolean hasAttributes(TypeElement type) {
        for (var method : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(type))) {
            if (method.getSimpleName().contentEquals("createAttributes") && method.getParameters().isEmpty()
                    && method.getModifiers().contains(Modifier.PUBLIC) && method.getModifiers().contains(Modifier.STATIC)
                    && processingEnv.getTypeUtils().erasure(method.getReturnType()).toString()
                    .equals("net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder")) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param type tested class
     * @param base base type name
     * @return whether type implements/extends
     * base
     */
    private boolean inherits(TypeElement type, String base) {
        var parent = processingEnv.getElementUtils().getTypeElement(base);
        return parent != null
                && processingEnv
                .getTypeUtils()
                .isAssignable(
                        processingEnv.getTypeUtils().erasure(type.asType()),
                        processingEnv.getTypeUtils().erasure(parent.asType()));
    }

    /**
     * @param type       concrete class
     * @param parameters exact erased parameter names
     * @return whether a
     * matching public constructor exists
     */
    private boolean hasConstructor(TypeElement type, String... parameters) {
        for (var constructor : ElementFilter.constructorsIn(type.getEnclosedElements())) {
            if (!constructor.getModifiers().contains(Modifier.PUBLIC)
                    || constructor.getParameters().size() != parameters.length) continue;
            boolean matches = true;
            for (int index = 0; index < parameters.length; index++) {
                if (!processingEnv
                        .getTypeUtils()
                        .erasure(constructor.getParameters().get(index).asType())
                        .toString()
                        .equals(parameters[index])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return true;
        }
        return false;
    }

    /**
     * @param type   annotated class
     * @param member annotation attribute
     * @return textual annotation
     * value
     */
    private String value(TypeElement type, String member) {
        for (var annotation : type.getAnnotationMirrors()) {
            if (!annotation.getAnnotationType().toString().equals(ANNOTATION)) continue;
            for (var entry :
                    processingEnv
                            .getElementUtils()
                            .getElementValuesWithDefaults(annotation)
                            .entrySet())
                if (entry.getKey().getSimpleName().contentEquals(member))
                    return entry.getValue().getValue().toString();
        }
        return "";
    }

    /**
     * @param id candidate registry id
     * @return whether its syntax and length are valid
     */
    private boolean identifier(String id) {
        return id.length() <= 256 && id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+");
    }

    /**
     * Writes even an empty resource after the last annotation is removed.
     */
    private void writeIndex(Map<String, TypeElement> entries, String path) {
        try {
            var resource =
                    processingEnv
                            .getFiler()
                            .createResource(
                                    StandardLocation.CLASS_OUTPUT,
                                    "",
                                    path,
                                    entries.values().toArray(Element[]::new));
            try (var writer = resource.openWriter()) {
                for (var name : entries.keySet()) writer.write(name + "\n");
            }
        } catch (IOException failure) {
            error(null, "Cannot write content index: " + failure.getMessage());
        }
    }

    /**
     * @param element invalid declaration or null
     * @param message actionable compilation error
     */
    private void error(Element element, String message) {
        invalid = true;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
