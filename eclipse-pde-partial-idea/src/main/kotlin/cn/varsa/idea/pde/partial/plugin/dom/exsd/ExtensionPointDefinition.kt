package cn.varsa.idea.pde.partial.plugin.dom.exsd

import cn.varsa.idea.pde.partial.plugin.dom.*
import cn.varsa.idea.pde.partial.plugin.dom.cache.*
import cn.varsa.idea.pde.partial.plugin.openapi.*
import com.intellij.openapi.project.*
import com.intellij.util.*
import org.jdom.*
import org.jdom.filter2.*
import org.jdom.input.*
import org.jdom.xpath.*
import org.jetbrains.kotlin.idea.core.util.*
import org.jetbrains.kotlin.utils.addToStdlib.*
import java.io.*

class ExtensionPointDefinition {
    companion object {
        const val schemaProtocol = "schema://"

        private val schemaInfoPath = XPathFactory.instance().compile(
            "/schema/annotation/appinfo/meta.schema | /schema/annotation/appInfo/meta.schema", Filters.element()
        )
        private val includePath = XPathFactory.instance().compile("/schema/include", Filters.element())
        private val extensionPath =
            XPathFactory.instance().compile("/schema/element[@name='extension']", Filters.element())
        private val elementPath =
            XPathFactory.instance().compile("/schema/element[@name!='extension']", Filters.element())
    }

    val plugin: String
    val id: String
    val name: String

    val point: String get() = id.takeIf { it.startsWith(plugin) } ?: "$plugin.$id"
    val includes: List<String>
    val extension: ElementDefinition?
    val elements: List<ElementDefinition>

    constructor(stream: InputStream) {
        val document = SAXBuilder().apply { saxHandlerFactory = NameSpaceCleanerFactory() }.build(stream)
        schemaInfoPath.evaluateFirst(document).also {
            plugin = it.getAttributeValue("plugin")
            id = it.getAttributeValue("id")
            name = it.getAttributeValue("name")
        }
        includes = includePath.evaluate(document).mapNotNull { it.getAttributeValue("schemaLocation") }
        extension = extensionPath.evaluateFirst(document)?.let(::ElementDefinition)
        elements = elementPath.evaluate(document).map(::ElementDefinition)
    }

    constructor(input: DataInput) {
        plugin = input.readUTF()
        id = input.readUTF()
        name = input.readUTF()

        includes = input.readStringList()
        extension = input.readNullable { ElementDefinition(input) }

        elements = (0 until input.readInt()).map { ElementDefinition(input) }
    }

    fun save(out: DataOutput) {
        out.writeUTF(plugin)
        out.writeUTF(id)
        out.writeUTF(name)

        out.writeStringList(includes)
        out.writeNullable(extension) { it.save(out) }

        out.writeInt(elements.size)
        elements.forEach { it.save(out) }
    }

    fun findRefElement(
        ref: ElementRefDefinition, project: Project
    ): ElementDefinition? =
        findRefElement(this, ref, project, ExtensionPointCacheService.getInstance(project), hashSetOf())

    private fun findRefElement(
        definition: ExtensionPointDefinition,
        ref: ElementRefDefinition,
        project: Project,
        cacheService: ExtensionPointCacheService,
        includeVisited: HashSet<ExtensionPointDefinition>
    ): ElementDefinition? {
        if (!includeVisited.add(definition)) return null
        return definition.elements.firstOrNull { it.name == ref.ref }
            ?: definition.includes.mapNotNull { schemaLocation ->
                cacheService.loadExtensionPoint(schemaLocation).also {
                    if (it == null) {
                        PdeNotifier.getInstance(project).important(
                            "Schema Not Found", "Schema not existed for ${definition.point} at location $schemaLocation"
                        )
                    }
                }
            }.firstNotNullResult { findRefElement(it, ref, project, cacheService, includeVisited) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ExtensionPointDefinition

        if (plugin != other.plugin) return false
        if (id != other.id) return false
        if (name != other.name) return false
        if (includes != other.includes) return false
        if (extension != other.extension) return false
        if (elements != other.elements) return false

        return true
    }

    override fun hashCode(): Int {
        var result = plugin.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + includes.hashCode()
        result = 31 * result + (extension?.hashCode() ?: 0)
        result = 31 * result + elements.hashCode()
        return result
    }

    override fun toString(): String {
        return "ExtensionPointDefinition(plugin='$plugin', id='$id', name='$name', point='$point', includes=$includes, extension=$extension, elements=$elements)"
    }
}

class ElementDefinition {
    companion object {
        private val elementDeprecatedPath = XPathFactory.instance()
            .compile("annotation/appinfo/meta.element | annotation/appInfo/meta.element", Filters.element())
        private val elementRefPath =
            XPathFactory.instance().compile("complexType/descendant::element", Filters.element())
        private val attributePath = XPathFactory.instance().compile("complexType/attribute", Filters.element())
    }

    val name: String
    val type: String?
    val deprecated: Boolean

    val elementRefs: List<ElementRefDefinition>
    val attributes: List<AttributeDefinition>

    constructor(element: Element) {
        name = element.getAttributeValue("name")
        type = element.getAttributeValue("type")
        deprecated = elementDeprecatedPath.evaluateFirst(element)?.getAttributeBooleanValue("deprecated") ?: false
        elementRefs = elementRefPath.evaluate(element).map(::ElementRefDefinition)
        attributes = attributePath.evaluate(element).map(::AttributeDefinition)
    }

    constructor(input: DataInput) {
        name = input.readUTF()
        type = input.readNullable { readUTF() }
        deprecated = input.readBoolean()

        elementRefs = (0 until input.readInt()).map { ElementRefDefinition(input) }

        attributes = (0 until input.readInt()).map { AttributeDefinition(input) }
    }

    fun save(out: DataOutput) {
        out.writeUTF(name)
        out.writeNullable(type) { out.writeUTF(it) }
        out.writeBoolean(deprecated)

        out.writeInt(elementRefs.size)
        elementRefs.forEach { it.save(out) }

        out.writeInt(attributes.size)
        attributes.forEach { it.save(out) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ElementDefinition

        if (name != other.name) return false
        if (type != other.type) return false
        if (deprecated != other.deprecated) return false
        if (elementRefs != other.elementRefs) return false
        if (attributes != other.attributes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (type?.hashCode() ?: 0)
        result = 31 * result + deprecated.hashCode()
        result = 31 * result + elementRefs.hashCode()
        result = 31 * result + attributes.hashCode()
        return result
    }

    override fun toString(): String {
        return "ElementDefinition(name='$name', type=$type, deprecated=$deprecated, elementRefs=$elementRefs, attributes=$attributes)"
    }
}

class ElementRefDefinition {
    val ref: String
    val minOccurs: Int
    val maxOccurs: Int

    constructor(element: Element) {
        ref = element.getAttributeValue("ref")
        minOccurs = element.getAttributeValue("minOccurs")?.toIntOrNull() ?: 1
        maxOccurs = element.getAttributeValue("maxOccurs")?.run { toIntOrNull() ?: -1 } ?: 1
    }

    constructor(input: DataInput) {
        ref = input.readUTF()
        minOccurs = input.readInt()
        maxOccurs = input.readInt()
    }

    fun save(out: DataOutput) {
        out.writeUTF(ref)
        out.writeInt(minOccurs)
        out.writeInt(maxOccurs)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ElementRefDefinition

        if (ref != other.ref) return false
        if (minOccurs != other.minOccurs) return false
        if (maxOccurs != other.maxOccurs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = ref.hashCode()
        result = 31 * result + minOccurs
        result = 31 * result + maxOccurs
        return result
    }

    override fun toString(): String {
        return "ElementRefDefinition(ref='$ref', minOccurs=$minOccurs, maxOccurs=$maxOccurs)"
    }
}

class AttributeDefinition {
    companion object {
        private val metaPath = XPathFactory.instance()
            .compile("annotation/appinfo/meta.attribute | annotation/appInfo/meta.attribute", Filters.element())
        private val simplePath = XPathFactory.instance().compile("simpleType/restriction", Filters.element())
        private val simpleEnumerationPath = XPathFactory.instance().compile("enumeration", Filters.element())
    }

    val name: String
    val type: String?
    val use: String?
    val value: String?

    val kind: String?
    val basedOn: String?
    val deprecated: Boolean

    val simpleBaseType: String?
    val simpleEnumeration: List<String>?

    constructor(element: Element) {
        name = element.getAttributeValue("name")
        type = element.getAttributeValue("type")
        use = element.getAttributeValue("use")
        value = element.getAttributeValue("value")
        metaPath.evaluateFirst(element).also {
            kind = it?.getAttributeValue("kind")
            basedOn = it?.getAttributeValue("basedOn")
            deprecated = it?.getAttributeBooleanValue("deprecated") ?: false
        }
        simplePath.evaluateFirst(element).also { simple ->
            simpleBaseType = simple?.getAttributeValue("base")
            simpleEnumeration = simple?.let(simpleEnumerationPath::evaluate)?.map { it.getAttributeValue("value") }
        }
    }

    constructor(input: DataInput) {
        name = input.readUTF()
        type = input.readNullable { readUTF() }
        use = input.readNullable { readUTF() }
        value = input.readNullable { readUTF() }

        kind = input.readNullable { readUTF() }
        basedOn = input.readNullable { readUTF() }
        deprecated = input.readBoolean()

        simpleBaseType = input.readNullable { readUTF() }
        simpleEnumeration = input.readNullable { readStringList() }
    }

    fun save(out: DataOutput) {
        out.writeUTF(name)
        out.writeNullable(type) { out.writeUTF(it) }
        out.writeNullable(use) { out.writeUTF(it) }
        out.writeNullable(value) { out.writeUTF(it) }

        out.writeNullable(kind) { out.writeUTF(it) }
        out.writeNullable(basedOn) { out.writeUTF(it) }
        out.writeBoolean(deprecated)

        out.writeNullable(simpleBaseType) { out.writeUTF(it) }
        out.writeNullable(simpleEnumeration) { out.writeStringList(it) }
    }

    override fun toString(): String {
        return "AttributeDefinition(name='$name', type=$type, use=$use, value=$value, kind=$kind, basedOn=$basedOn, deprecated=$deprecated, simpleBaseType=$simpleBaseType, simpleEnumeration=$simpleEnumeration)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttributeDefinition

        if (name != other.name) return false
        if (type != other.type) return false
        if (use != other.use) return false
        if (value != other.value) return false
        if (kind != other.kind) return false
        if (basedOn != other.basedOn) return false
        if (deprecated != other.deprecated) return false
        if (simpleBaseType != other.simpleBaseType) return false
        if (simpleEnumeration != other.simpleEnumeration) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + (type?.hashCode() ?: 0)
        result = 31 * result + (use?.hashCode() ?: 0)
        result = 31 * result + (value?.hashCode() ?: 0)
        result = 31 * result + (kind?.hashCode() ?: 0)
        result = 31 * result + (basedOn?.hashCode() ?: 0)
        result = 31 * result + deprecated.hashCode()
        result = 31 * result + (simpleBaseType?.hashCode() ?: 0)
        result = 31 * result + (simpleEnumeration?.hashCode() ?: 0)
        return result
    }
}
