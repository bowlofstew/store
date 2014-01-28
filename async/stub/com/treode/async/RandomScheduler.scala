package com.treode.async

import scala.collection.mutable.{ArrayBuffer, PriorityQueue}
import scala.util.Random

private class RandomScheduler (random: Random) extends StubScheduler {

  private val tasks = new ArrayBuffer [Runnable]
  private val timers = new PriorityQueue [StubTimer]

  private var time = 0L

  def execute (task: Runnable): Unit =
    tasks.append (task)

  def delay (millis: Long, task: Runnable): Unit =
    timers.enqueue (StubTimer (time + millis, task))

  def at (millis: Long, task: Runnable): Unit =
    timers.enqueue (StubTimer (millis, task))

  def spawn (task: Runnable): Unit =
    tasks.append (task)

  def nextTask(): Unit = {
    val i = random.nextInt (tasks.size)
    val t = tasks (i)
    tasks (i) = tasks (tasks.size-1)
    tasks.reduceToSize (tasks.size-1)
    t.run()
  }

  def nextTimer() {
    val t = timers.dequeue()
    time = t.time
    t.task.run()
  }

  def runTasks (withTimers: Boolean) {
    while (!tasks.isEmpty || withTimers && !timers.isEmpty) {
      if (tasks.isEmpty)
        nextTimer()
      else
        nextTask()
    }}

  def shutdown (timeout: Long) = ()
}