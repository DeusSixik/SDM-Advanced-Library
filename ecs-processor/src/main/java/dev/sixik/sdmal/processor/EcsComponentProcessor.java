package dev.sixik.sdmal.processor;

import java.io.IOException;
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
 * Validates {@code @EcsComponent} declarations and indexes their binary names for ServiceLoader.
 * This processor has no Minecraft or runtime-library dependencies and runs on Java 17 and later.
 * It observes all compilations so removing the final annotation also writes an empty index.
 */
@SupportedAnnotationTypes("*")
public final class EcsComponentProcessor extends AbstractProcessor {
    /** Fully qualified annotation name resolved from the consumer's compilation classpath. */
    private static final String ANNOTATION = "dev.sixik.sdmal.api.ecs.annotations.EcsComponent";
    /** Service interface implemented by every automatically registered component. */
    private static final String SERVICE = "dev.sixik.sdmal.api.ecs.WorldObjectComponent";
    /** Sorted provider names with originating types, accumulated across processing rounds. */
    private final Map<String, TypeElement> components = new TreeMap<>();
    /** Stable ids already used within this compilation, for duplicate diagnostics. */
    private final Map<String, TypeElement> ids = new TreeMap<>();
    /** Whether a validation error prevents publishing a partial service index. */
    private boolean invalid;

    /**
     * Supports the compiler invoking the processor, without limiting consumers to Java 17 syntax.
     * @return latest source version supported by the running JDK
     */
    @Override public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * Collects annotated types and writes one deterministic service descriptor after the final round.
     * Other processors, including Mixin's processor, remain free to process all annotations.
     * @param annotations annotations visible in the current round
     * @param round current compilation round and its annotated elements
     * @return false because annotations are observed without claiming them
     */
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        TypeElement annotation = processingEnv.getElementUtils().getTypeElement(ANNOTATION);
        if (annotation == null) return false;
        if (round.processingOver()) {
            if (!invalid && !round.errorRaised()) writeIndex();
        } else {
            for (Element element : round.getElementsAnnotatedWith(annotation)) collect(element);
        }
        return false;
    }

    /**
     * Validates service visibility, component inheritance, constructor and stable identifier.
     * @param element annotated declaration from the compiler, never null
     */
    private void collect(Element element) {
        if (!(element instanceof TypeElement type) || element.getKind() != ElementKind.CLASS
                || type.getModifiers().contains(Modifier.ABSTRACT)) {
            error(element, "@EcsComponent requires a concrete class implementing " + SERVICE);
            return;
        }
        TypeElement service = processingEnv.getElementUtils().getTypeElement(SERVICE);
        if (service == null || !processingEnv.getTypeUtils().isAssignable(
                processingEnv.getTypeUtils().erasure(type.asType()),
                processingEnv.getTypeUtils().erasure(service.asType()))) {
            error(type, "@EcsComponent class must implement " + SERVICE);
            return;
        }
        for (Element enclosing = type; enclosing instanceof TypeElement; enclosing = enclosing.getEnclosingElement()) {
            if (!enclosing.getModifiers().contains(Modifier.PUBLIC)) {
                error(type, "ServiceLoader requires a public component and public enclosing classes");
                return;
            }
        }
        if (type.getNestingKind().isNested() && !type.getModifiers().contains(Modifier.STATIC)) {
            error(type, "ServiceLoader requires nested @EcsComponent classes to be static");
            return;
        }
        boolean constructor = ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
            .anyMatch(candidate -> candidate.getParameters().isEmpty()
                && candidate.getModifiers().contains(Modifier.PUBLIC));
        if (!constructor) {
            error(type, "ServiceLoader requires a public no-argument constructor on @EcsComponent");
            return;
        }
        String id = componentId(type);
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            error(type, "@EcsComponent id must be a lowercase namespaced identifier, for example yourmod:energy");
            return;
        }
        TypeElement previous = ids.putIfAbsent(id, type);
        if (previous != null && !previous.equals(type)) {
            error(type, "Duplicate @EcsComponent id '" + id + "' also used by " + previous.getQualifiedName());
            return;
        }
        components.put(processingEnv.getElementUtils().getBinaryName(type).toString(), type);
    }

    /**
     * Reads annotation metadata without loading the consumer's annotation or Minecraft classes.
     * @param type annotated component type
     * @return configured id, or an empty string for an incompatible annotation declaration
     */
    private String componentId(TypeElement type) {
        for (var annotation : type.getAnnotationMirrors()) {
            if (!annotation.getAnnotationType().toString().equals(ANNOTATION)) continue;
            for (var entry : processingEnv.getElementUtils().getElementValuesWithDefaults(annotation).entrySet()) {
                if (entry.getKey().getSimpleName().contentEquals("id")) return entry.getValue().getValue().toString();
            }
        }
        return "";
    }

    /** Writes binary class names to CLASS_OUTPUT; no Java sources or runtime bridge classes are generated. */
    private void writeIndex() {
        try {
            var resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "",
                "META-INF/services/" + SERVICE, components.values().toArray(Element[]::new));
            try (var writer = resource.openWriter()) {
                for (String component : components.keySet()) writer.write(component + "\n");
            }
        } catch (IOException failure) {
            error(null, "Cannot write ECS service descriptor: " + failure.getMessage());
        }
    }

    /**
     * Emits a compilation error and suppresses publication of an incomplete descriptor.
     * @param element offending source element, or null for a file-system failure
     * @param message diagnostic explaining the required correction
     */
    private void error(Element element, String message) {
        invalid = true;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
