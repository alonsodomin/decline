package com.monovore.decline

import cats.data.NonEmptyList
import cats.implicits._
import cats.{Alternative, Eval, Semigroup}
import com.monovore.decline.Opts.Name


case class Result[+A](get: Eval[Result.Value[A]]) {
  def andThen[B](f: A => Result[B]): Result[B] = Result(get.flatMap {
    case Result.Return(a) => f(a).get
    case missing @ Result.Missing(_) => Eval.now(missing)
    case fail @ Result.Fail(_) => Eval.now(fail)
  })
}

object Result {

  sealed trait Value[+A]

  case class Stuff(
    flags: List[Opts.Name] = Nil,
    commands: List[String] = Nil,
    argument: Boolean = false
  ) {

    def message: String = {
      val flagString =
        flags match {
          case Nil => None
          case one :: Nil => Some(s"flag $one")
          case _ => Some(flags.mkString("flag (", " or ", ")"))
        }

      val commandString =
        if (commands.isEmpty) None
        else Some(commands.mkString("command (", " or ", ")"))

      val argString = if (argument) Some("argument") else None

      s"Missing expected ${List(flagString, commandString, argString).flatten.mkString(", or ")}!"
    }
  }

  object Stuff {

    implicit val semigroup: Semigroup[Stuff] = new Semigroup[Stuff] {
      override def combine(x: Stuff, y: Stuff): Stuff =
        Stuff(
          x.flags ++ y.flags,
          x.commands ++ y.commands,
          x.argument || y.argument
        )
    }
  }

  case class Return[A](value: A) extends Value[A]
  case class Missing(flags: List[Stuff]) extends Value[Nothing]
  case class Fail(messages: List[String]) extends Value[Nothing]

  def success[A](value: A): Result[A] = Result(Eval.now(Return(value)))

  val missing = Result(Eval.now(Missing(Nil)))
  def missingFlag(flag: Opts.Name) = Result(Eval.now(Missing(List(Stuff(flags = List(flag))))))
  def missingCommand(command: String) = Result(Eval.now(Missing(List(Stuff(commands = List(command))))))
  def missingArgument = Result(Eval.now(Missing(List(Stuff(argument = true)))))

  def failure(messages: String*) = Result(Eval.now(Fail(messages.toList)))

  implicit val alternative: Alternative[Result] =
    new Alternative[Result] {

      override def pure[A](x: A): Result[A] = Result.success(x)

      override def ap[A, B](ff: Result[(A) => B])(fa: Result[A]): Result[B] = Result(
        (ff.get |@| fa.get).tupled.map {
          case (Return(f), Return(a)) => Return(f(a))
          case (Fail(l), Fail(r)) => Fail(l ++ r)
          case (Fail(l), Missing(r)) => Fail(l ++ r.map { _.message })
          case (Missing(l), Fail(r)) => Fail(l.map { _.message } ++ r)
          case (Fail(l), _) => Fail(l)
          case (_, Fail(r)) => Fail(r)
          case (Missing(l), Missing(r)) => Missing(l ++ r)
          case (Missing(l), _) => Missing(l)
          case (_, Missing(r)) => Missing(r)
        }
      )

      override def combineK[A](x: Result[A], y: Result[A]): Result[A] = Result(
        x.get.flatMap {
          case Missing(flags) => y.get.map {
            case Missing(moreFlags) => Missing((flags.headOption |+| moreFlags.headOption).toList)
            case other => other
          }
          case other => Eval.now(other)
        }
      )

      override def empty[A]: Result[A] = missing
    }
}

case class Parser[A](command: Command[A]) {

  import Parser._

  def apply(args: List[String]): Either[Help, A] = {
    consumeAll(args, Accumulator.fromOpts(command.options))
  }

  def failure(reason: String*): Either[Help, A] = Left(Help.fromCommand(command).withErrors(reason.toList))

  def fromOut(out: Result[A]): Either[Help, A] = out.get.value match {
    case Result.Return(value) => Right(value)
    case Result.Missing(stuff) => failure(stuff.map { _.message }: _*)
    case Result.Fail(messages) => failure(messages: _*)
  }

  def consumeAll(args: List[String], accumulator: Accumulator[A]): Either[Help, A] = args match {
    case LongOptWithEquals(option, value) :: rest => accumulator.parseOption(Opts.LongName(option)) match {
      case Unmatched => failure(s"Unexpected option: --$option")
      case Ambiguous => failure(s"Ambiguous option: --$option")
      case MatchFlag(next) => failure(s"Got unexpected value for flag: --$option")
      case MatchOption(next) => consumeAll(rest, next(value))
    }
    case LongOpt(option) :: rest => accumulator.parseOption(Opts.LongName(option)) match {
      case Unmatched => failure(s"Unexpected option: --$option")
      case Ambiguous => failure(s"Ambiguous option: --$option")
      case MatchFlag(next) => consumeAll(rest, next)
      case MatchOption(next) => rest match {
        case Nil => failure(s"Missing value for option: --$option")
        case value :: rest0 => consumeAll(rest0, next(value))
      }
    }
    case "--" :: rest => consumeArgs(rest, accumulator)
    case ShortOpt(NonEmptyString(flag, tail)) :: rest => {

      def consumeShort(char: Char, tail: String, accumulator: Accumulator[A]): Either[Help, A] =
        accumulator.parseOption(Opts.ShortName(char)) match {
          case Unmatched => failure(s"Unexpected option: -$flag")
          case Ambiguous => failure(s"Ambiguous option: -$flag")
          case MatchFlag(next) => tail match {
            case "" => consumeAll(rest, next)
            case NonEmptyString(nextFlag, nextTail) => consumeShort(nextFlag, nextTail, next)
          }
          case MatchOption(next) => tail match {
            case "" => rest match {
              case Nil => failure(s"Missing value for option: -$flag")
              case value :: rest0 => consumeAll(rest0, next(value))
            }
            case _ => consumeAll(rest, next(tail))
          }
        }

      consumeShort(flag, tail, accumulator)
    }
    case arg :: rest =>
      accumulator.parseSub(arg)
        .map { next => consumeAll(rest, next) }
        .orElse {
          accumulator.parseArg(arg).map { consumeAll(rest, _) }
        }
        .getOrElse(failure(s"Unexpected argument: $arg"))
    case Nil => fromOut(accumulator.result)
  }

  def consumeArgs(args: List[String], accumulator: Accumulator[A]): Either[Help, A] = args match {
    case Nil => fromOut(accumulator.result)
    case arg :: rest => {
      accumulator.parseArg(arg)
        .map { next => consumeArgs(rest, next) }
        .getOrElse { failure(s"Unexpected argument: $arg")}
    }
  }
}

object Parser {

  sealed trait OptionResult[+A]
  case object Unmatched extends OptionResult[Nothing]
  case class MatchFlag[A](next: Accumulator[A]) extends OptionResult[A]
  case class MatchOption[A](next: String => Accumulator[A]) extends OptionResult[A]
  case object Ambiguous extends OptionResult[Nothing]

  trait Accumulator[+A] {
    def parseOption(name: Opts.Name): OptionResult[A]
    def parseArg(arg: String): Option[Accumulator[A]]
    def parseSub(command: String): Option[Accumulator[A]]
    def result: Result[A]
  }

  val LongOpt = "--(.+)".r
  val LongOptWithEquals= "--(.+?)=(.+)".r
  val ShortOpt = "-(.+)".r

  object NonEmptyString {
    def unapply(string: String): Option[(Char, String)] =
      if (string.isEmpty) None
      else Some(string.charAt(0) -> string.substring(1))
  }

  object Accumulator {

    case class Pure[A](value: Result[A]) extends Accumulator[A] {
      override def parseOption(name: Name): OptionResult[A] = Unmatched

      override def parseArg(arg: String): Option[Accumulator[A]] = None

      override def parseSub(command: String): Option[Accumulator[A]] = None

      override def result = value
    }

    case class App[X, A](left: Accumulator[X => A], right: Accumulator[X]) extends Accumulator[A] {

      override def parseOption(name: Opts.Name): OptionResult[A] = {
        (left.parseOption(name), right.parseOption(name)) match {
          case (Unmatched, Unmatched) => Unmatched
          case (Unmatched, MatchFlag(nextRight)) => MatchFlag(App(left, nextRight))
          case (Unmatched, MatchOption(nextRight)) => MatchOption { value => App(left, nextRight(value)) }
          case (MatchFlag(nextLeft), Unmatched) => MatchFlag(App(nextLeft, right))
          case (MatchOption(nextLeft), Unmatched) => MatchOption { value => App(nextLeft(value), right) }
          case _ => Ambiguous
        }
      }

      override def parseArg(arg: String): Option[Accumulator[A]] = {
        left.parseArg(arg).map { App(_, right) } orElse
          right.parseArg(arg).map { App(left, _) }
      }

      override def parseSub(command: String): Option[Accumulator[A]] =
        left.parseSub(command).map { App(_, Pure(right.result)) } orElse
          right.parseSub(command).map { App(Pure(left.result), _) }

      override def result = left.result ap right.result
    }

    case class OrElse[A](left: Accumulator[A], right: Accumulator[A]) extends Accumulator[A] {

      override def parseOption(name: Name): OptionResult[A] = {
        (left.parseOption(name), right.parseOption(name)) match {
          case (Unmatched, Unmatched) => Unmatched
          case (Unmatched, MatchFlag(nextRight)) => MatchFlag(nextRight)
          case (Unmatched, MatchOption(nextRight)) => MatchOption { value => nextRight(value) }
          case (MatchFlag(nextLeft), Unmatched) => MatchFlag(nextLeft)
          case (MatchOption(nextLeft), Unmatched) => MatchOption { value => nextLeft(value) }
          case _ => Ambiguous
        }
      }

      override def parseArg(arg: String): Option[Accumulator[A]] = {
        (left.parseArg(arg), right.parseArg(arg)) match {
          case (Some(newLeft), Some(newRight)) => Some(OrElse(newLeft, newRight))
          case (Some(newLeft), None) => Some(newLeft)
          case (None, Some(newRight)) => Some(newRight)
          case (None, None) => None
        }
      }

      override def parseSub(command: String): Option[Accumulator[A]] = {
        left.parseSub(command) orElse right.parseSub(command)
      }

      override def result = left.result <+> right.result
    }

    case class Regular(names: List[Opts.Name], values: List[String] = Nil) extends Accumulator[NonEmptyList[String]] {

      override def parseOption(name: Opts.Name) =
        if (names contains name) MatchOption { value => copy(values = value :: values)}
        else Unmatched

      override def parseArg(arg: String) = None

      override def parseSub(command: String) = None

      def result =
        NonEmptyList.fromList(values.reverse)
          .map(Result.success)
          .getOrElse(Result.missingFlag(names.head))
    }

    case class Flag(names: List[Opts.Name], values: Int = 0) extends Accumulator[NonEmptyList[Unit]] {

      override def parseOption(name: Opts.Name) =
        if (names contains name) MatchFlag(copy(values = values + 1))
        else Unmatched

      override def parseArg(arg: String) = None

      override def parseSub(command: String) = None

      def result =
        NonEmptyList.fromList(List.fill(values)(()))
          .map(Result.success)
          .getOrElse(Result.missingFlag(names.head))
    }

    case class Argument(limit: Int, values: List[String] = Nil) extends Accumulator[NonEmptyList[String]] {

      override def parseOption(name: Opts.Name) = Unmatched

      override def parseArg(arg: String) =
        if (values.size < limit) Some(copy(values = arg :: values))
        else None

      override def parseSub(command: String) = None

      def result =
        NonEmptyList.fromList(values.reverse)
          .map(Result.success)
          .getOrElse(Result.missingArgument)
    }

    case class Subcommand[A](name: String, action: Accumulator[A]) extends Accumulator[A] {

      override def parseOption(name: Name): OptionResult[A] = Unmatched

      override def parseArg(arg: String): Option[Accumulator[A]] = None

      override def parseSub(command: String): Option[Accumulator[A]] =
        if (command == name) Some(action) else None

      override def result = Result.missingCommand(name)
    }

    case class Validate[A, B](a: Accumulator[A], f: A => Result[B]) extends Accumulator[B] {

      override def parseOption(name: Opts.Name) =
        a.parseOption(name) match {
          case Unmatched => Unmatched
          case MatchFlag(next) => MatchFlag(copy(a = next))
          case MatchOption(next) => MatchOption { value => copy(a = next(value)) }
          case Ambiguous => Ambiguous
        }

      override def parseArg(arg: String) =
        a.parseArg(arg).map { Validate(_, f) }

      override def parseSub(command: String) =
        a.parseSub(command).map { Validate(_, f) }

      override def result = a.result.andThen(f)
    }


    def repeated[A](opt: Opt[A]): Accumulator[NonEmptyList[A]] = opt match {
      case Opt.Regular(name, _, _) => Regular(name)
      case Opt.Flag(name, _) => Flag(name)
      case Opt.Argument(_) => Argument(Int.MaxValue)
    }

    def fromOpts[A](opts: Opts[A]): Accumulator[A] = opts match {
      case Opts.Pure(a) => Accumulator.Pure(a)
      case Opts.App(f, a) => Accumulator.App(fromOpts(f), fromOpts(a))
      case Opts.OrElse(a, b) => OrElse(fromOpts(a), fromOpts(b))
      case Opts.Validate(a, validation) => Validate(fromOpts(a), validation)
      case Opts.Subcommand(command) => Subcommand(command.name, fromOpts(command.options))
      case Opts.Single(opt) => opt match {
        case Opt.Regular(name, _, _) => Validate(Regular(name), { v: NonEmptyList[String] => Result.success(v.toList.last) })
        case Opt.Flag(name, _) => Validate(Flag(name), { v: NonEmptyList[Unit] => Result.success(v.toList.last) })
        case Opt.Argument(_) => Validate(Argument(1), { args: NonEmptyList[String] => Result.success(args.head)})
      }
      case Opts.Repeated(opt) => repeated(opt)
    }
  }
}