package smellar

import cats.effect.IO

enum LogLevel:
  case Off, Verbose, Debug

object LogLevel:
  def parse(raw: String): Option[LogLevel] =
    raw.trim.toLowerCase match
      case "off"     => Some(Off)
      case "verbose" => Some(Verbose)
      case "debug"   => Some(Debug)
      case _         => None

  /** `SMELLAR_LOG=verbose|debug` turns on diagnostics without a flag. `--verbose`/`--debug` win. */
  def fromEnv(env: Map[String, String]): LogLevel =
    env.get("SMELLAR_LOG").flatMap(parse).getOrElse(Off)

/** Diagnostics go to stderr; stdout carries only the payload, so it stays safe to pipe. */
final class Log(level: LogLevel):
  private def enabled(min: LogLevel): Boolean = level.ordinal >= min.ordinal

  def info(message: => String): IO[Unit]  = emit(LogLevel.Verbose, message)
  def warn(message: => String): IO[Unit]  = emit(LogLevel.Verbose, s"warning: $message")
  def debug(message: => String): IO[Unit] = emit(LogLevel.Debug, message)

  def debugTrace(t: Throwable): IO[Unit] =
    if enabled(LogLevel.Debug) then IO.blocking(t.printStackTrace()) else IO.unit

  private def emit(min: LogLevel, message: => String): IO[Unit] =
    if enabled(min) then IO.blocking(System.err.println(s"[smellar] $message")) else IO.unit

object Log:
  val off: Log = Log(LogLevel.Off)
