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
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;

/**
 * Validates typed async requests and emits their resource-only discovery index.
 */
@SupportedAnnotationTypes("*")
public final class AsyncRequestProcessor extends AbstractProcessor {
    /**
     * Runtime annotation name.
     */
    private static final String ANNOTATION = "dev.sixik.sdmal.api.network.annotations.NetworkRequest";
    /**
     * Async request interface erasure.
     */
    private static final String REQUEST = "dev.sixik.sdmal.api.network.AsyncRequest";
    /**
     * Sorted binary names and source origins.
     */
    private final Map<String, TypeElement> requests = new TreeMap<>();
    /**
     * IDs used in this compilation.
     */
    private final Map<String, TypeElement> ids = new TreeMap<>();
    /**
     * Whether an invalid declaration prevents index output.
     */
    private boolean invalid;

    /**
     * @return source level supported by the compiler
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    /**
     * Observes all rounds and writes an empty index after removal of the last request.
     *
     * @param annotations current annotation set
     * @param round       compiler round
     * @return false to allow other processors
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
        if (!(element instanceof TypeElement type) || (type.getKind() != ElementKind.CLASS && type.getKind() != ElementKind.RECORD) || type.getModifiers().contains(Modifier.ABSTRACT)) {
            error(element, "@NetworkRequest requires a concrete class or record");
            return;
        }
        var request = processingEnv.getElementUtils().getTypeElement(REQUEST);
        if (request == null || !processingEnv.getTypeUtils().isAssignable(processingEnv.getTypeUtils().erasure(type.asType()), processingEnv.getTypeUtils().erasure(request.asType()))) {
            error(type, "@NetworkRequest must implement " + REQUEST);
            return;
        }
        if (!type.getTypeParameters().isEmpty()) {
            error(type, "Async request type parameters must be concrete");
            return;
        }
        for (Element enclosing = type; enclosing instanceof TypeElement; enclosing = enclosing.getEnclosingElement())
            if (!enclosing.getModifiers().contains(Modifier.PUBLIC)) {
                error(type, "Async requests and enclosing classes must be public");
                return;
            }
        if (type.getNestingKind().isNested() && !type.getModifiers().contains(Modifier.STATIC)) {
            error(type, "Nested async requests must be static");
            return;
        }
        if (type.getKind() != ElementKind.RECORD && ElementFilter.constructorsIn(type.getEnclosedElements()).stream().noneMatch(constructor -> constructor.getParameters().isEmpty())) {
            error(type, "Async request classes require a no-argument constructor; use a record for immutable requests");
            return;
        }
        if (type.getKind() != ElementKind.RECORD) {
            for (var field : ElementFilter.fieldsIn(processingEnv.getElementUtils().getAllMembers(type))) {
                var modifiers = field.getModifiers();
                if (!modifiers.contains(Modifier.STATIC) && !modifiers.contains(Modifier.TRANSIENT) && modifiers.contains(Modifier.FINAL)) {
                    error(field, "Final serialized request fields require a record");
                    return;
                }
            }
        }
        TypeMirror result = resultType(type.asType());
        TypeMirror response = responseType(type);
        if (result == null || response == null || !processingEnv.getTypeUtils().isSameType(result, response)) {
            error(type, "@NetworkRequest response must match the concrete AsyncRequest<R> type (use Void.class for acknowledgements)");
            return;
        }
        String id = value(type, "id");
        String timeout = value(type, "timeoutMillis");
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || id.length() > 256) {
            error(type, "Async request id must be a lowercase namespaced identifier of at most 256 characters");
            return;
        }
        try {
            long milliseconds = Long.parseLong(timeout);
            if (milliseconds <= 0 || milliseconds > 86_400_000L) throw new NumberFormatException();
        } catch (NumberFormatException failure) {
            error(type, "Async request timeoutMillis must be in (0, 86400000]");
            return;
        }
        var previous = ids.putIfAbsent(id, type);
        if (previous != null && !previous.equals(type)) {
            error(type, "Duplicate async request id " + id);
            return;
        }
        requests.put(processingEnv.getElementUtils().getBinaryName(type).toString(), type);
    }

    /**
     * @param type declaration @param member annotation member @return source value
     */
    private String value(TypeElement type, String member) {
        for (var annotation : type.getAnnotationMirrors())
            if (annotation.getAnnotationType().toString().equals(ANNOTATION))
                for (var value : processingEnv.getElementUtils().getElementValuesWithDefaults(annotation).entrySet())
                    if (value.getKey().getSimpleName().contentEquals(member))
                        return value.getValue().getValue().toString();
        return "";
    }

    /**
     * @param type concrete supertype @return substituted AsyncRequest result type or null for a raw contract
     */
    private TypeMirror resultType(TypeMirror type) {
        if (type instanceof DeclaredType declared && ((TypeElement) declared.asElement()).getQualifiedName().contentEquals(REQUEST))
            return declared.getTypeArguments().size() == 1 ? declared.getTypeArguments().get(0) : null;
        for (var parent : processingEnv.getTypeUtils().directSupertypes(type)) {
            var result = resultType(parent);
            if (result != null) return result;
        }
        return null;
    }

    /**
     * @param type annotated declaration @return declared response class mirror
     */
    private TypeMirror responseType(TypeElement type) {
        for (var annotation : type.getAnnotationMirrors()) {
            if (!annotation.getAnnotationType().toString().equals(ANNOTATION)) continue;
            for (var entry : processingEnv.getElementUtils().getElementValuesWithDefaults(annotation).entrySet())
                if (entry.getKey().getSimpleName().contentEquals("response"))
                    return (TypeMirror) entry.getValue().getValue();
        }
        return null;
    }

    /**
     * Writes binary request names to the class output.
     */
    private void writeIndex() {
        try {
            var resource = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/sdmal/network-requests", requests.values().toArray(Element[]::new));
            try (var writer = resource.openWriter()) {
                for (var name : requests.keySet()) writer.write(name + "\n");
            }
        } catch (IOException failure) {
            error(null, "Cannot write async request index: " + failure.getMessage());
        }
    }

    /**
     * @param element invalid source or null @param message compilation diagnostic
     */
    private void error(Element element, String message) {
        invalid = true;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
