package toothpick.compiler.common;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.inject.Inject;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.JavaFileObject;
import toothpick.compiler.common.generators.CodeGenerator;
import toothpick.compiler.common.generators.targets.ParamInjectionTarget;
import toothpick.compiler.memberinjector.targets.FieldInjectionTarget;

import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PROTECTED;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.tools.Diagnostic.Kind.ERROR;
import static javax.tools.Diagnostic.Kind.WARNING;

/**
 * Base processor class.
 */
public abstract class ToothpickProcessor extends AbstractProcessor {

  /** The name of the {@link javax.inject.Inject} annotation class that triggers {@code ToothpickProcessor}s. */
  public static final String INJECT_ANNOTATION_CLASS_NAME = "javax.inject.Inject";
  public static final String SINGLETON_ANNOTATION_CLASS_NAME = "javax.inject.Singleton";
  public static final String PRODUCES_SINGLETON_ANNOTATION_CLASS_NAME = "toothpick.ProvidesSingletonInScope";

  /**
   * The name of the annotation processor option to declare in which package a registry should be generated.
   * If this parameter is not passed, no registry is generated.
   */
  public static final String PARAMETER_REGISTRY_PACKAGE_NAME = "toothpick_registry_package_name";

  /**
   * The name of the annotation processor option to exclude classes from the creation of member scopes & factories.
   * Exclude filters are java regex, multiple entries are comma separated.
   */
  public static final String PARAMETER_EXCLUDES = "toothpick_excludes";

  /**
   * The name of the annotation processor option to let TP know about custom scope annotation classes.
   * This option is needed only in the case where a custom scope annotation is used on a class, and this
   * class doesn't use any annotation processed out of the box by TP (i.e. javax.inject.* annotations).
   * If you use custom scope annotations, it is a good practice to always use this option so that
   * developers can use the new scope annotation in a very free way without having to consider the annotation
   * processing internals.
   */
  public static final String PARAMETER_ANNOTATION_TYPES = "toothpick_annotations";

  /**
   * The name annotation processor option to declare in which packages reside the sub-registries used by the generated registry,
   * if it is created. Multiple entries are comma separated.
   *
   * @see #PARAMETER_REGISTRY_PACKAGE_NAME
   */
  public static final String PARAMETER_REGISTRY_CHILDREN_PACKAGE_NAMES = "toothpick_registry_children_package_names";

  protected Elements elementUtils;
  protected Types typeUtils;
  protected Filer filer;

  protected String toothpickRegistryPackageName;
  protected List<String> toothpickRegistryChildrenPackageNameList;
  protected String toothpickExcludeFilters = "java.*,android.*";
  protected Set<String> supportedAnnotationTypes = new HashSet<>();
  private boolean hasAlreadyRun;

  @Override
  public synchronized void init(ProcessingEnvironment processingEnv) {
    super.init(processingEnv);

    elementUtils = processingEnv.getElementUtils();
    typeUtils = processingEnv.getTypeUtils();
    filer = processingEnv.getFiler();
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  public void addSupportedAnnotationType(String typeFQN) {
    supportedAnnotationTypes.add(typeFQN);
  }

  protected void wasRun() {
    hasAlreadyRun = true;
  }

  protected boolean hasAlreadyRun() {
    return hasAlreadyRun;
  }

  protected boolean writeToFile(CodeGenerator codeGenerator, String fileDescription, Element... originatingElements) {
    Writer writer = null;
    boolean success = true;

    try {
      JavaFileObject jfo = filer.createSourceFile(codeGenerator.getFqcn(), originatingElements);
      writer = jfo.openWriter();
      writer.write(codeGenerator.brewJava());
    } catch (IOException e) {
      error("Error writing %s file: %s", fileDescription, e.getMessage());
      success = false;
    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException e) {
          error("Error closing %s file: %s", fileDescription, e.getMessage());
          success = false;
        }
      }
    }

    return success;
  }

  /**
   * Reads both annotation compilers {@link ToothpickProcessor#PARAMETER_REGISTRY_PACKAGE_NAME} and
   * {@link ToothpickProcessor#PARAMETER_REGISTRY_CHILDREN_PACKAGE_NAMES} and
   * {@link ToothpickProcessor#PARAMETER_EXCLUDES}
   * options from the arguments passed to the processor.
   */
  protected void readCommonProcessorOptions() {
    readOptionRegistryPackageName();
    readOptionRegistryChildrenPackageNames();
    readOptionExcludes();
  }

  private void readOptionRegistryPackageName() {
    Map<String, String> options = processingEnv.getOptions();
    if (toothpickRegistryPackageName == null) {
      toothpickRegistryPackageName = options.get(PARAMETER_REGISTRY_PACKAGE_NAME);
    }
    if (toothpickRegistryPackageName == null) {
      warning("No option -A%s to the compiler." + " No registries will be generated.", PARAMETER_REGISTRY_PACKAGE_NAME);
    }
  }

  private void readOptionRegistryChildrenPackageNames() {
    Map<String, String> options = processingEnv.getOptions();
    if (toothpickRegistryChildrenPackageNameList == null) {
      toothpickRegistryChildrenPackageNameList = new ArrayList<>();
      String toothpickRegistryChildrenPackageNames = options.get(PARAMETER_REGISTRY_CHILDREN_PACKAGE_NAMES);
      if (toothpickRegistryChildrenPackageNames != null) {
        String[] registryPackageNames = toothpickRegistryChildrenPackageNames.split(",");
        for (String registryPackageName : registryPackageNames) {
          toothpickRegistryChildrenPackageNameList.add(registryPackageName.trim());
        }
      }
    }
    if (toothpickRegistryChildrenPackageNameList == null) {
      warning("No option -A%s was passed to the compiler." + " No sub registries will be used.", PARAMETER_REGISTRY_CHILDREN_PACKAGE_NAMES);
    }
  }

  private void readOptionExcludes() {
    Map<String, String> options = processingEnv.getOptions();
    if (options.containsKey(PARAMETER_EXCLUDES)) {
      toothpickExcludeFilters = options.get(PARAMETER_EXCLUDES);
    }
  }

  protected void readOptionAnnotationTypes() {
    Map<String, String> options = processingEnv.getOptions();
    if (options.containsKey(PARAMETER_ANNOTATION_TYPES)) {
      String additionalAnnotationTypes = options.get(PARAMETER_ANNOTATION_TYPES);
      for (String additionalAnnotationType : additionalAnnotationTypes.split(",")) {
        supportedAnnotationTypes.add(additionalAnnotationType.trim());
      }
    }
  }

  protected void error(String message, Object... args) {
    processingEnv.getMessager().printMessage(ERROR, String.format(message, args));
  }

  protected void error(Element element, String message, Object... args) {
    processingEnv.getMessager().printMessage(ERROR, String.format(message, args), element);
  }

  protected void warning(Element element, String message, Object... args) {
    processingEnv.getMessager().printMessage(WARNING, String.format(message, args), element);
  }

  protected void warning(String message, Object... args) {
    processingEnv.getMessager().printMessage(WARNING, String.format(message, args));
  }

  protected boolean isValidInjectAnnotatedFieldOrParameter(VariableElement variableElement) {
    TypeElement enclosingElement = (TypeElement) variableElement.getEnclosingElement();

    // Verify modifiers.
    Set<Modifier> modifiers = variableElement.getModifiers();
    if (modifiers.contains(PRIVATE)) {
      error(variableElement, "@Inject annotated fields must be non private : %s#%s", enclosingElement.getQualifiedName(),
          variableElement.getSimpleName());
      return false;
    }

    // Verify parentScope modifiers.
    Set<Modifier> parentModifiers = enclosingElement.getModifiers();
    if (parentModifiers.contains(PRIVATE)) {
      error(variableElement, "@Injected fields in class %s. The class must be non private.", enclosingElement.getSimpleName());
      return false;
    }

    if (!isValidInjectedType(variableElement)) {
      return false;
    }
    return true;
  }

  protected boolean isValidInjectAnnotatedMethod(ExecutableElement methodElement) {
    TypeElement enclosingElement = (TypeElement) methodElement.getEnclosingElement();

    // Verify modifiers.
    Set<Modifier> modifiers = methodElement.getModifiers();
    if (modifiers.contains(PRIVATE)) {
      error(methodElement, "@Inject annotated methods must not be private : %s#%s", enclosingElement.getQualifiedName(),
          methodElement.getSimpleName());
      return false;
    }

    // Verify parentScope modifiers.
    Set<Modifier> parentModifiers = enclosingElement.getModifiers();
    if (parentModifiers.contains(PRIVATE)) {
      error(methodElement, "@Injected fields in class %s. The class must be non private.", enclosingElement.getSimpleName());
      return false;
    }

    for (VariableElement paramElement : methodElement.getParameters()) {
      if (!isValidInjectedType(paramElement)) {
        return false;
      }
    }

    if (modifiers.contains(PUBLIC) || modifiers.contains(PROTECTED)) {
      warning(methodElement, "@Inject annotated methods should have protect visibility: %s#%s", enclosingElement.getQualifiedName(),
          methodElement.getSimpleName());
    }
    return true;
  }

  protected boolean isValidInjectedType(VariableElement injectedTypeElement) {
    if (!isValidInjectedElementKind(injectedTypeElement)) {
      return false;
    }
    if (isProviderOrLazy(injectedTypeElement) && !isValidProviderOrLazy(injectedTypeElement)) {
      return false;
    }
    return true;
  }

  private boolean isValidInjectedElementKind(VariableElement injectedTypeElement) {
    Element typeElement = typeUtils.asElement(injectedTypeElement.asType());
    if (typeElement.getKind() != ElementKind.CLASS //
        && typeElement.getKind() != ElementKind.INTERFACE //
        && typeElement.getKind() != ElementKind.ENUM) {

      //find the class containing the element
      //the element can be a field or a parameter
      Element enclosingElement = injectedTypeElement.getEnclosingElement();
      if (enclosingElement instanceof TypeElement) {
        error(injectedTypeElement, "Field %s#%s is of type %s which is not supported by Toothpick.",
            ((TypeElement) enclosingElement).getQualifiedName(), injectedTypeElement.getSimpleName(), typeElement);
      } else {
        Element methodOrConstructorElement = enclosingElement;
        enclosingElement = enclosingElement.getEnclosingElement();
        error(injectedTypeElement, "Parameter %s in method/constructor %s#%s is of type %s which is not supported by Toothpick.",
            injectedTypeElement.getSimpleName(), //
            ((TypeElement) enclosingElement).getQualifiedName(), //
            methodOrConstructorElement.getSimpleName(), //
            typeElement);
      }
      return false;
    }
    return true;
  }

  private boolean isValidProviderOrLazy(Element element) {
    DeclaredType declaredType = (DeclaredType) element.asType();

    // Contains type parameter
    if (declaredType.getTypeArguments().isEmpty()) {
      Element enclosingElement = element.getEnclosingElement();
      if (enclosingElement instanceof TypeElement) {
        error(element, "Field %s#%s is not a valid %s.", ((TypeElement) enclosingElement).getQualifiedName(), element.getSimpleName(), declaredType);
      } else {
        error(element, "Parameter %s in method/constructor %s#%s is not a valid %s.", element.getSimpleName(), //
            ((TypeElement) enclosingElement.getEnclosingElement()).getQualifiedName(), //
            enclosingElement.getSimpleName(), declaredType);
      }
      return false;
    }

    TypeMirror firstParameterTypeMirror = declaredType.getTypeArguments().get(0);
    if (firstParameterTypeMirror.getKind() == TypeKind.DECLARED) {
      int size = ((DeclaredType) firstParameterTypeMirror).getTypeArguments().size();
      if (size != 0) {
        Element enclosingElement = element.getEnclosingElement();
        error(element, "Lazy/Provider %s is not a valid in %s. Lazy/Provider cannot be used on generic types.",
            element.getSimpleName(), //
            enclosingElement.getSimpleName());
        return false;
      }
    }

    return true;
  }

  protected List<ParamInjectionTarget> getParamInjectionTargetList(ExecutableElement executableElement) {
    List<ParamInjectionTarget> paramInjectionTargetList = new ArrayList<>();
    for (VariableElement variableElement : executableElement.getParameters()) {
      paramInjectionTargetList.add(createFieldOrParamInjectionTarget(variableElement));
    }
    return paramInjectionTargetList;
  }

  protected FieldInjectionTarget createFieldOrParamInjectionTarget(VariableElement variableElement) {
    final TypeElement memberTypeElement = (TypeElement) typeUtils.asElement(variableElement.asType());
    final String memberName = variableElement.getSimpleName().toString();

    ParamInjectionTarget.Kind kind = getParamInjectionTargetKind(variableElement);
    TypeElement kindParameterTypeElement = getInjectedType(variableElement);

    String name = findQualifierName(variableElement);

    return new FieldInjectionTarget(memberTypeElement, memberName, kind, kindParameterTypeElement, name);
  }

  /**
   * Retrieves the type of a field or param. The type can be the type of the parameter
   * in the java way (e.g. {@code B b}, type is {@code B}); but it can also be the type of
   * a {@link toothpick.Lazy} or {@link javax.inject.Provider}
   * (e.g. {@code Lazy&lt;B&gt; b}, type is {@code B} not {@code Lazy}).
   *
   * @param variableElement the field or variable element. NOT his type !
   * @return the type has defined above.
   */
  protected TypeElement getInjectedType(VariableElement variableElement) {
    final TypeElement fieldType;
    if (getParamInjectionTargetKind(variableElement) == ParamInjectionTarget.Kind.INSTANCE) {
      fieldType = (TypeElement) typeUtils.asElement(typeUtils.erasure(variableElement.asType()));
    } else {
      fieldType = getKindParameter(variableElement);
    }
    return fieldType;
  }

  protected boolean isExcludedByFilters(TypeElement typeElement) {
    String typeElementName = typeElement.getQualifiedName().toString();
    for (String exclude : toothpickExcludeFilters.split(",")) {
      String regEx = exclude.trim();
      if (typeElementName.matches(regEx)) {
        warning(typeElement,
            "The class %s was excluded by filters set at the annotation processor level. " + "No factory will be generated by toothpick.",
            typeElement.getQualifiedName());
        return true;
      }
    }
    return false;
  }

  //overrides are simpler in this case as methods can only be package or protected.
  //a method with the same name in the type hierarchy would necessarily mean that
  //the {@code methodElement} would be an override of this method.
  protected boolean isOverride(TypeElement typeElement, ExecutableElement methodElement) {
    TypeElement currentTypeElement = typeElement;
    do {
      if (currentTypeElement != typeElement) {
        List<? extends Element> enclosedElements = currentTypeElement.getEnclosedElements();
        for (Element enclosedElement : enclosedElements) {
          if (enclosedElement.getSimpleName().equals(methodElement.getSimpleName())
              && enclosedElement.getAnnotation(Inject.class) != null
              && enclosedElement.getKind() == ElementKind.METHOD) {
            return true;
          }
        }
      }
      TypeMirror superclass = currentTypeElement.getSuperclass();
      if (superclass.getKind() == TypeKind.DECLARED) {
        DeclaredType superType = (DeclaredType) superclass;
        currentTypeElement = (TypeElement) superType.asElement();
      } else {
        currentTypeElement = null;
      }
    } while (currentTypeElement != null);
    return false;
  }

  protected TypeElement getMostDirectSuperClassWithInjectedMembers(TypeElement typeElement, boolean onlyParents) {
    TypeElement currentTypeElement = typeElement;
    do {
      if (currentTypeElement != typeElement || !onlyParents) {
        List<? extends Element> enclosedElements = currentTypeElement.getEnclosedElements();
        for (Element enclosedElement : enclosedElements) {
          if ((enclosedElement.getAnnotation(Inject.class) != null && enclosedElement.getKind() == ElementKind.FIELD)
              || (enclosedElement.getAnnotation(Inject.class) != null && enclosedElement.getKind() == ElementKind.METHOD)) {
            return currentTypeElement;
          }
        }
      }
      TypeMirror superclass = currentTypeElement.getSuperclass();
      if (superclass.getKind() == TypeKind.DECLARED) {
        DeclaredType superType = (DeclaredType) superclass;
        currentTypeElement = (TypeElement) superType.asElement();
      } else {
        currentTypeElement = null;
      }
    } while (currentTypeElement != null);
    return null;
  }

  /**
   * Lookup both {@link javax.inject.Qualifier} and {@link javax.inject.Named}
   * to provide the name of an injection.
   *
   * @param element the element for which a qualifier is to be found.
   * @return the name of this element or null if it has no qualifier annotations.
   */
  private String findQualifierName(VariableElement element) {
    String name = null;
    if (element.getAnnotationMirrors().isEmpty()) {
      return name;
    }

    for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
      TypeElement annotationTypeElement = (TypeElement) annotationMirror.getAnnotationType().asElement();
      if (isSameType(annotationTypeElement, "javax.inject.Named")) {
        checkIfAlreadyHasName(element, name);
        name = getValueOfAnnotation(annotationMirror);
      } else if (annotationTypeElement.getAnnotation(javax.inject.Qualifier.class) != null) {
        checkIfAlreadyHasName(element, name);
        name = annotationTypeElement.getQualifiedName().toString();
      }
    }
    return name;
  }

  protected boolean isSameType(TypeElement typeElement, String typeName) {
    return isSameType(typeElement.asType(), typeName);
  }

  private boolean isSameType(TypeMirror typeMirror, String typeName) {
    return typeUtils.isSameType(typeUtils.erasure(typeMirror), typeUtils.erasure(elementUtils.getTypeElement(typeName).asType()));
  }

  private void checkIfAlreadyHasName(VariableElement element, Object name) {
    if (name != null) {
      error(element, "Only one javax.inject.Qualifier annotation is allowed to name injections.");
    }
  }

  protected String getValueOfAnnotation(AnnotationMirror annotationMirror) {
    String result = null;
    for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> annotationParamEntry : annotationMirror.getElementValues().entrySet()) {
      if (annotationParamEntry.getKey().getSimpleName().contentEquals("value")) {
        result = annotationParamEntry.getValue().toString().replaceAll("\"", "");
      }
    }
    return result;
  }

  private boolean isProviderOrLazy(Element element) {
    FieldInjectionTarget.Kind kind = getParamInjectionTargetKind(element);
    return kind == ParamInjectionTarget.Kind.PROVIDER || kind == ParamInjectionTarget.Kind.LAZY;
  }

  private FieldInjectionTarget.Kind getParamInjectionTargetKind(Element variableElement) {
    TypeMirror elementTypeMirror = variableElement.asType();
    if (isSameType(elementTypeMirror, "javax.inject.Provider")) {
      return FieldInjectionTarget.Kind.PROVIDER;
    } else if (isSameType(elementTypeMirror, "toothpick.Lazy")) {
      return FieldInjectionTarget.Kind.LAZY;
    } else {
      Element typeElement = typeUtils.asElement(variableElement.asType());
      if (typeElement.getKind() != ElementKind.CLASS //
          && typeElement.getKind() != ElementKind.INTERFACE //
          && typeElement.getKind() != ElementKind.ENUM) {

        Element enclosingElement = variableElement.getEnclosingElement();
        while (!(enclosingElement instanceof TypeElement)) {
          enclosingElement = enclosingElement.getEnclosingElement();
        }
        error(variableElement, "Field %s#%s is of type %s which is not supported by Toothpick.", ((TypeElement) enclosingElement).getQualifiedName(),
            variableElement.getSimpleName(), typeElement);
        return null;
      }
      return FieldInjectionTarget.Kind.INSTANCE;
    }
  }

  private TypeElement getKindParameter(Element element) {
    TypeMirror elementTypeMirror = element.asType();
    TypeMirror firstParameterTypeMirror = ((DeclaredType) elementTypeMirror).getTypeArguments().get(0);
    return (TypeElement) typeUtils.asElement(typeUtils.erasure(firstParameterTypeMirror));
  }

  protected boolean isNonStaticInnerClass(TypeElement typeElement) {
    Element outerClassOrPackage = typeElement.getEnclosingElement();
    if (outerClassOrPackage.getKind() != ElementKind.PACKAGE && !typeElement.getModifiers().contains(Modifier.STATIC)) {
      error(typeElement, "Class %s is a non static inner class. @Inject constructors are not allowed in non static inner classes.",
          typeElement.getQualifiedName());
      return true;
    }
    return false;
  }
}
