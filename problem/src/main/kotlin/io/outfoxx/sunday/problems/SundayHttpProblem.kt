package io.outfoxx.sunday.problems

import com.fasterxml.jackson.annotation.JsonAnyGetter
import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import io.outfoxx.sunday.http.Status
import io.outfoxx.sunday.problems.ProblemFactory.Descriptor
import java.net.URI

open class SundayHttpProblem(
  val type: URI,
  val title: String?,
  val status: Int?,
  val detail: String?,
  val instance: URI?,
  @JsonIgnore
  val extensions: MutableMap<String, Any?> = mutableMapOf(),
) : Problem(createMessage(type, title, status, detail, instance, extensions)) {

  @JsonAnyGetter
  fun extensions(): Map<String, Any?> = extensions

  @JsonAnySetter
  fun extension(
    name: String,
    value: Any?,
  ) {
    extensions[name] = value
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is SundayHttpProblem) return false
    if (type != other.type) return false
    if (title != other.title) return false
    if (status != other.status) return false
    if (detail != other.detail) return false
    if (instance != other.instance) return false
    if (extensions != other.extensions) return false
    return true
  }

  override fun hashCode(): Int {
    var result = type.hashCode()
    result = 31 * result + (title?.hashCode() ?: 0)
    result = 31 * result + (status?.hashCode() ?: 0)
    result = 31 * result + (detail?.hashCode() ?: 0)
    result = 31 * result + (instance?.hashCode() ?: 0)
    result = 31 * result + extensions.hashCode()
    return result
  }

  override fun toString(): String =
    "SundayHttpProblem(type=$type, title=$title, status=$status, detail=$detail, instance=$instance, extensions=$extensions)"

  fun copy(
    type: URI = this.type,
    title: String? = this.title,
    status: Int? = this.status,
    detail: String? = this.detail,
    instance: URI? = this.instance,
    extensions: MutableMap<String, Any?> = this.extensions,
  ): SundayHttpProblem = SundayHttpProblem(type, title, status, detail, instance, extensions)

  object Factory : ProblemFactory {

    override fun typed(type: URI): ProblemFactory.Builder = Builder(Descriptor(type = type))

    override fun from(status: Status): ProblemFactory.Builder =
      Builder(Descriptor(status = status.code, title = status.reasonPhrase))

    override fun from(descriptor: Descriptor): Problem = fromDescriptor(descriptor)

    override fun adapter(): ProblemAdapter = Adapter

  }

  object Adapter : ProblemAdapter {

    private val Problem.sunday: SundayHttpProblem? get() = this as? SundayHttpProblem

    override fun getType(problem: Problem): URI = problem.sunday?.type ?: URI.create("about:blank")

    override fun getTitle(problem: Problem): String? = problem.sunday?.title

    override fun getStatus(problem: Problem): Status? = problem.sunday?.status?.let(Status::valueOf)

    override fun getDetail(problem: Problem): String? = problem.sunday?.detail

    override fun getInstance(problem: Problem): URI? = problem.sunday?.instance

    override fun getExtensions(problem: Problem): Map<String, Any?> = problem.sunday?.extensions ?: mapOf()

  }

  class Builder(
    private var descriptor: Descriptor = Descriptor(),
  ) : ProblemFactory.Builder {

    override fun detail(detail: String?): Builder {
      descriptor = descriptor.copy(detail = detail)
      return this
    }

    override fun instance(uri: URI): Builder {
      descriptor = descriptor.copy(instance = uri)
      return this
    }

    override fun extension(
      name: String,
      value: Any?,
    ): Builder {
      descriptor = descriptor.copy(extensions = descriptor.extensions + (name to value))
      return this
    }

    override fun build(): SundayHttpProblem = fromDescriptor(descriptor)
  }

  companion object {

    private fun fromDescriptor(descriptor: Descriptor): SundayHttpProblem =
      SundayHttpProblem(
        descriptor.type,
        descriptor.title,
        descriptor.status,
        descriptor.detail,
        descriptor.instance,
        descriptor.extensions.toMutableMap(),
      )

    fun createMessage(
      type: URI?,
      title: String?,
      status: Int?,
      detail: String?,
      instance: URI?,
      extensions: Map<String, Any?>,
    ): String {
      val standard: Map<String, Any?> =
        mapOf(
          "type" to type.toString(),
          "title" to title,
          "status" to status,
          "detail" to detail,
          "instance" to instance,
        )
      return (standard + extensions)
        .filter { it.value != null }
        .entries
        .joinToString(", ") { "${it.key}: ${it.value}" }
    }
  }

}
