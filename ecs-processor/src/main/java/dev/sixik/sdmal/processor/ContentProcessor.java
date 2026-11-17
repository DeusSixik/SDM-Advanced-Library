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
                    "dev.sixik.sdmal.api.content.ContentTab");

    /**
     * Sorted binary names and originating source elements.
     */
    private final Map<String, TypeElement> types = new TreeMap<>();

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
     * @param round compiler processing round
     * @return false to permit other processors
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        var annotation = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
        if (annotation == null) return false;
        if (round.processingOver()) {
            if (!invalid && !round.errorRaised()) writeIndex();
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
            error(type, "Content must extend Item, Block, BlockEntity or implement ContentTab");
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
        if (kind == 3 && !tab.isEmpty()) {
            error(type, "ContentTab cannot belong to another tab");
            return;
        }
        var properties = "net.minecraft.world.item.Item.Properties";
        boolean validConstructor =
                switch (kind) {
                    case 0 ->
                            inherits(type, "net.minecraft.world.item.BlockItem")
                                    ? hasConstructor(
                                            type,
                                            "net.minecraft.world.level.block.Block",
                                            properties)
                                    : hasConstructor(type, properties);
                    case 1 ->
                            hasConstructor(
                                    type,
                                    "net.minecraft.world.level.block.state.BlockBehaviour.Properties");
                    case 2 ->
                            hasConstructor(
                                            type,
                                            "net.minecraft.world.level.block.entity.BlockEntityType",
                                            "net.minecraft.core.BlockPos",
                                            "net.minecraft.world.level.block.state.BlockState")
                                    || hasConstructor(
                                            type,
                                            "net.minecraft.core.BlockPos",
                                            "net.minecraft.world.level.block.state.BlockState");
                    default -> hasConstructor(type);
                };
        if (!validConstructor) {
            error(
                    type,
                    "Unsupported public content constructor: use Item.Properties,"
                        + " BlockBehaviour.Properties, (Block, Item.Properties) for BlockItem,"
                        + " (BlockEntityType, BlockPos, BlockState) for BlockEntity, or noargs for"
                        + " ContentTab");
            return;
        }
        var previous = ids.putIfAbsent(kind + ":" + id, type);
        if (previous != null && !previous.equals(type)) {
            error(
                    type,
                    "Duplicate content registry id "
                            + id
                            + " also declared by "
                            + previous.getQualifiedName());
            return;
        }
        types.put(processingEnv.getElementUtils().getBinaryName(type).toString(), type);
    }

    /**
     * @param type tested class
     * @param base base type name
     * @return whether type implements/extends
     *     base
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
     * @param type concrete class
     * @param parameters exact erased parameter names
     * @return whether a
     *     matching public constructor exists
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
     * @param type annotated class
     * @param member annotation attribute
     * @return textual annotation
     *     value
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
    private void writeIndex() {
        try {
            var resource =
                    processingEnv
                            .getFiler()
                            .createResource(
                                    StandardLocation.CLASS_OUTPUT,
                                    "",
                                    "META-INF/sdmal/content",
                                    types.values().toArray(Element[]::new));
            try (var writer = resource.openWriter()) {
                for (var name : types.keySet()) writer.write(name + "\n");
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
