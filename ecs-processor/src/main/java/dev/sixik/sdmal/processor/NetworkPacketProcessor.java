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
 * Validates packets and writes a deterministic class index; never generates consumer-facing Java.
 */
@SupportedAnnotationTypes("*")
public final class NetworkPacketProcessor extends AbstractProcessor {
    /**
     * Runtime annotation name, resolved without loading the consumer's classes.
     */
    private static final String ANNOTATION =
            "dev.sixik.sdmal.api.network.annotations.NetworkPacket";

    /**
     * Required packet contract.
     */
    private static final String PACKET = "dev.sixik.sdmal.api.network.Packet";

    /**
     * Sorted binary names with incremental compilation origins.
     */
    private final Map<String, TypeElement> packets = new TreeMap<>();

    /**
     * Ids used in this compilation.
     */
    private final Map<String, TypeElement> ids = new TreeMap<>();

    /**
     * Whether diagnostics prevent publishing an incomplete index.
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
     * Observes packet declarations and writes even an empty index when the final packet is removed.
     *
     * @param annotations annotations in this round
     * @param round       compiler round
     * @return false so other processors can inspect the same declarations
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
     * Validates the automatic construction and discovery contract.
     *
     * @param element annotated declaration
     */
    private void collect(Element element) {
        if (!(element instanceof TypeElement type)
                || (element.getKind() != ElementKind.CLASS
                && element.getKind() != ElementKind.RECORD)
                || element.getModifiers().contains(Modifier.ABSTRACT)) {
            error(
                    element,
                    "@NetworkPacket requires a concrete class or record implementing Packet");
            return;
        }
        var contract = processingEnv.getElementUtils().getTypeElement(PACKET);
        if (contract == null
                || !processingEnv.getTypeUtils().isAssignable(type.asType(), contract.asType())) {
            error(type, "@NetworkPacket must implement " + PACKET);
            return;
        }
        if (!type.getTypeParameters().isEmpty()) {
            error(type, "Packet type parameters must be concrete");
            return;
        }
        for (Element enclosing = type;
             enclosing instanceof TypeElement;
             enclosing = enclosing.getEnclosingElement()) {
            if (!enclosing.getModifiers().contains(Modifier.PUBLIC)) {
                error(type, "Indexed packets and enclosing classes must be public");
                return;
            }
        }
        if (type.getNestingKind().isNested() && !type.getModifiers().contains(Modifier.STATIC)) {
            error(type, "Nested packets must be static");
            return;
        }
        if (type.getKind() != ElementKind.RECORD) {
            boolean noArg =
                    ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                            .anyMatch(candidate -> candidate.getParameters().isEmpty());
            if (!noArg) {
                error(
                        type,
                        "Packet classes require a no-argument constructor; use a record for"
                                + " immutable payloads");
                return;
            }
            for (var field :
                    ElementFilter.fieldsIn(processingEnv.getElementUtils().getAllMembers(type))) {
                var modifiers = field.getModifiers();
                if (modifiers.contains(Modifier.STATIC) || modifiers.contains(Modifier.TRANSIENT))
                    continue;
                if (modifiers.contains(Modifier.FINAL)) {
                    error(field, "Final serialized packet fields require a record");
                    return;
                }
            }
        }
        String id = "";
        for (var annotation : type.getAnnotationMirrors()) {
            if (!annotation.getAnnotationType().toString().equals(ANNOTATION)) continue;
            for (var value :
                    processingEnv
                            .getElementUtils()
                            .getElementValuesWithDefaults(annotation)
                            .entrySet()) {
                if (value.getKey().getSimpleName().contentEquals("id"))
                    id = value.getValue().getValue().toString();
            }
        }
        if (id.length() > 256 || !id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) {
            error(
                    type,
                    "Packet id must be a lowercase namespaced identifier of at most 256"
                            + " characters");
            return;
        }
        var previous = ids.putIfAbsent(id, type);
        if (previous != null && !previous.equals(type)) {
            error(type, "Duplicate packet id " + id);
            return;
        }
        packets.put(processingEnv.getElementUtils().getBinaryName(type).toString(), type);
    }

    /**
     * Writes binary names to CLASS_OUTPUT with all originating packet declarations.
     */
    private void writeIndex() {
        try {
            var resource =
                    processingEnv
                            .getFiler()
                            .createResource(
                                    StandardLocation.CLASS_OUTPUT,
                                    "",
                                    "META-INF/sdmal/network-packets",
                                    packets.values().toArray(Element[]::new));
            try (var writer = resource.openWriter()) {
                for (var name : packets.keySet()) writer.write(name + "\n");
            }
        } catch (IOException failure) {
            error(null, "Cannot write packet index: " + failure.getMessage());
        }
    }

    /**
     * Emits a build error and suppresses incomplete metadata.
     *
     * @param element invalid declaration or null for IO failures
     * @param message actionable diagnostic
     */
    private void error(Element element, String message) {
        invalid = true;
        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
