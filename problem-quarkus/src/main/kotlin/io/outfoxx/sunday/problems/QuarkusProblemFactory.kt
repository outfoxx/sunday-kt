package io.outfoxx.sunday.problems

import io.outfoxx.sunday.http.Status
import io.outfoxx.sunday.problems.ProblemFactory.Descriptor
import io.quarkiverse.resteasy.problem.HttpProblem
import java.net.URI

object QuarkusProblemFactory : ProblemFactory {

  object Adapter : ProblemAdapter {

    private fun asHttpProblem(problem: Problem): HttpProblem? = problem as? HttpProblem

    override fun getType(problem: Problem): URI = asHttpProblem(problem)?.type ?: URI.create("about:blank")

    override fun getTitle(problem: Problem): String? = asHttpProblem(problem)?.title

    override fun getStatus(problem: Problem): Status? = asHttpProblem(problem)?.statusCode?.let(Status::valueOf)

    override fun getDetail(problem: Problem): String? = asHttpProblem(problem)?.detail

    override fun getInstance(problem: Problem): URI? = asHttpProblem(problem)?.instance

    override fun getExtensions(problem: Problem): Map<String, Any?> = asHttpProblem(problem)?.parameters ?: mapOf()
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

    override fun build(): Problem = fromDescriptor(descriptor)
  }

  override fun typed(type: URI): ProblemFactory.Builder = Builder(Descriptor(type = type))

  override fun from(status: Status): ProblemFactory.Builder =
    Builder(Descriptor(status = status.code, title = status.reasonPhrase))

  override fun from(descriptor: Descriptor): Problem = fromDescriptor(descriptor)

  override fun adapter(): ProblemAdapter = Adapter

  private fun fromDescriptor(descriptor: Descriptor): Problem {
    val builder =
      HttpProblem
        .builder()
        .apply {
          withType(descriptor.type)
          descriptor.title?.let(::withTitle)
          descriptor.status?.let(::withStatus)
          descriptor.detail?.let(::withDetail)
          descriptor.instance?.let(::withInstance)
          descriptor.extensions.forEach { (name, value) -> with(name, value) }
        }
    return builder.build()
  }

}
