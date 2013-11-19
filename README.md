Probe
=========

Probe: systematic capture for dynamic program state

## Introduction

Text-based logging is an unfortunate historical artifact. A log
statement serializes a small portion of dynamic program state in a
human-readable string. While these strings are trivial to store,
route, manipulate, and inspect - in modern systems they require a
great deal of development and operations work to aggregate and
programmatically analyze.  What if we could capture and manipulate
traces of program behavior in a more accessible manner?

Moving from logs to 'probes', or capturing the state of a program
value at a point in time, enables us to attach a wide variety of
comptuational stages between the probe point and human-consumption of
that state.  This works particularly well for functional systems where
state is predominantly immutable.  

This library facilitates the insertion of 'probe' points into your
source code as well as various dynamic contexts (such as state change
events or function invocations).  It provides facilities for
subscribing to subsets of this state across the program, applying
filters and other transforms to it, and routing it to any number of
sinks.

The state map produced by probe points can serve as logging
statements, but may also be used for a wide variety of additional
purposes such as profiling, auditing, forensics, usage statistics,
etc.  Probe also provides a simple logging API compability layer
supporting the Pedestal-style structured logging interface.
(Clojure.tools.logging compability coming soon).

How about monitoring probe state across a distributed application?
Rather than using Scribe or Splunk to aggregate and parse text
strings, fire up [Reimann](http://reimann.io) and pipe probe state to
it or use a scalable data store like HBase, MongoDB, Cassandra, or
DynamoDB where historical probes can be indexed and analyzed as
needed?  Cassandra is especially nice as you can have it automatically
expire log data at different times based, perhaps, on a priority
field.

An alternative approach to this concept is
[Lamina](https://github.com/ztellman/lamina), introduced by a [nice
talk](http://vimeo.com/45132054#!) that closely shares the philosophy
behind Probe.  I wrote probe as a minimalist replacement for logging
infrastructure in a distributed application and think it is more
accessible than Lamina, but YMMV.  The more the merrier!

## Installation

Add Probe to your lein project.clj :dependencies

    [com.vitalreactor/probe "0.9.0"]

And use it from your applications:

    (:require [probe.core :as p]
	          [probe.sink :as sink]
              [probe.logging :as log])
    
Probe and log statements look like this:

    (p/probe :msg "This is a test" :value 10) 
    (log/info :msg "This is a test" :value 10)


See the examples below for details on how probe state is generated by
these statements, how to create sinks, and how to route probe state to
sinks with an optional transform channel.

## Concepts

* Probe statement - Any program statement that extracts dynamic state during
  program execution. A probe will typically return some subset of
  lexical, dynamic, and/or host information as well as explicit
  user-provided data.  Probes can include function
  entry/exit/exception events as well or tie into foundational notification
  mechanisms such as adding a 'watcher probe' to an agent or atom.
   * Function probe - Default probes that can be added to any Var holding a function value
   * Watcher probe - Probe state changes on any Atom, Ref, Var, or Agent.
* Probe state - A kv-map generated by a probe statement
* Tags - Probe expressions accept one or more tags to help filter and route state.
    Built-in probe statements generate a specific set of tags.
* Sink - A function that takes probe state values and disposes them in some
    way, typically to a logging subsystem, the console, memory or a database.
* Subscriptions - This is the library's glue, consisting of a Selector, an
    optional core.async Channel, and a sink name.
* Selector - a conjunction of tags that must be present for the probe state to 
    be pushed onto the channel and on to the sink.

Reserved state keys

- Probe statements: :thread-id, :tags, :ns, :line, :ts
- Expression probe: :expr, :value
- Function probes: :fname, :fn, :args, :return, :exception

Reserved tags:

- Namespace tags: :ns/*
- Function probes: :probe/fn, :probe/fn-exit, :probe/fn-enter, :probe/fn-except
- Watcher probes: :probe/watch
- Standard logging levels: :trace, :debug, :info, :warn, :error

## Documentation by Example

    (require '[probe.core :as p])
	(require '[probe.sink :as sink]
	(require '[core.async :as async])

Start with a simple console sink

	(p/add-sink :printer sink/console-raw)

Let's watch some test probe points:

    (p/subscribe #{:test} :printer)

	(p/probe [:test] :value 10)
	=> nil    
	{:ts #inst "2013-11-19T01:21:57.109-00:00", :thread-id 307, :ns probe.core, :tags #{:test :ns/probe.core :ns/probe}, :line 1, :value 10}

Probe state is only sent to the sink when the selector matches the
tags.  In fact, the entire probe expression is conditional on there
being at least one matching probe for the tags.

    (p/probe [:foo] :value 10)
	=> nil

We can use a core.async transform channel to watch just the values and timestamp:

    (p/subscribe #{:test} :printer
        (async/map> #(select-keys % [:ts :value]) (async/chan)))

    (p/probe #{:debug} :value 10)
	=> nil
    {:ts #inst "2013-11-19T01:25:37.348-00:00", :value 10}

What subscriptions do we have now?

	(p/subscriptions) 
	=> ([#{:test} :printer])

Notice that our update clobbered the prior subscription.  We can grab
the complete subscription or sink value to get a better sense of
internals:

    (p/get-subscription #{:test} :printer)
    => {:selector #{:test}, :channel #<async$map_GT_$reify__26869 clojure.core.async$map_GT_$reify__26869@f7503>, :sink :printer}

Here we see a selector which determines whether probes are submitted
at all, the channel to push the state to, and the sink that channel is
connected to.

    (p/get-sink :printer)
	=> {:name :printer, :function #<sink$console_raw probe.sink$console_raw@1ff3ef9>, :in #<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@7f528f>, :mix #<async$mix$reify__27625 clojure.core.async$mix$reify__27625@105559f>, :out #<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@13874b7>}

Sinks uses a core.async mix to accept inputs from multiple
subscriptions and pipes them to the sink handler function.  If you
want to do something for every state submitted to a sink, you can just
wrap the sink function when creating the sink.  For any short-term or
source specific transforms, use subscription transform channels.  For
transforms performed system wide, write your own macro that wraps the
main probe macro used above and injects whatever data you care about,
or have a standard way of generating application-specific transform
channels.

Core.async channels are composable, so you can compose a set of standard
mapping, filtering channels into what you need for a specific purpose.

Let's explore some other probing conveniences.  For example, good
functional code comes pre-packaged with some wonderful probe points
called functions.

    (def testprobe [a b]
	  (+ a b))

    (p/probe-fn! #{:test} 'testprobe)

	(testprobe 1 2)
    => 3

    (p/subscribe #{:test} :printer) ;; stomp on our earlier filter

	(testprobe 1 2)
  	=> 3
    {:ts #inst "2013-11-19T01:36:28.237-00:00", :thread-id 321, :ns probe.core, :tags #{:test :ns/probe.core :ns/probe :probe/fn-enter}, :args (1 2), :fn :enter, :line 1, :fname testprobe}
    {:ts #inst "2013-11-19T01:36:28.237-00:00", :thread-id 321, :ns probe.core, :tags #{:probe/fn-exit :test :ns/probe.core :ns/probe}, :return 3, :args (1 2), :fn :exit, :line 1, :fname testprobe}

We can now magically trace input arguments and return values for every
expression.  How about just focusing on the input/outputs?  We can use
some channel builders from the probe.core package to make this more concise.

    (defn args-and-value [state] (select-keys state [:args :value :fname]))
    (p/subscribe #{:test :probe/fn-exit} :printer (p/map> args-and-value))

	(map #(testprobe 1 %) (repeat 0 10))
  	=> (1 2 3 4 5 6 7 8 9 10)
    {:fname testprobe :args (1 0) :value 1}
    {:fname testprobe :args (1 1) :value 1}
	...

So far, we've just been printing stuff out.  Not much better than
current logging solutions.  What if we want to capture these vectors,
or a function trace from from deep inside a larger system for
interactive replay at the repl?

    (def my-trace (sink/make-memory))
	(p/add-sink :accum (sink/memory-sink my-trace))
	(p/subscribe #{:test :probe/fn-exit} :accum (p/map> args-and-value))

	(map #(testprobe 1 %) (range 0 10))
    => (1 2 3 4 5 6 7 8 9 10)

    (sink/scan-memory)
    => ({:fname testprobe, :value 1 :args (1 0)} {:fname testprobe, :value 2 :args (1 1)} ...)

    (map :value (sink/scan-memory))
    => (1 2 3 4 5 6 7 8 9 10)

    (def my-trace nil)  ;; remove state from namespace for GC
	(unprobe-fn! 'testprobe) ;; Remove the function probe wrapper
    (p/rem-sink :accum) ;; also removes the probe subscription for you

We can also watch state elements like Refs and Vars by applying a transform function that generates a probe by applying the transform-fn to the new value whenever the state is changed:

    (def myatom (atom {:test 1}))
    (def myref (ref {:test 1}))
    (probe-state! #{:test} identity #'myref)
    (probe-state! #{:test} identity #'myatom)
    (p/subscribe #{:test :probe/watch} :printer)

    (swap! myatom update-in [:test] inc)
    => {:test 2}
    {:ts #inst "2013-11-19T19:55:03.849-00:00", :ns probe.core, :test 2, :thread-id 97, :tags #{:test :probe/watch :ns/probe.core :ns/probe}}

    (dosync
      (commute myref update-in [:test] inc))
    => {:test 2}
	{:ts #inst "2013-11-19T19:58:24.961-00:00", :ns probe.core, :test 2, :thread-id 103, :tags #{:test :probe/watch :ns/probe.core :ns/probe}}

Note: probing alter-var-root operations on namespace vars is still a little shaky so don't rely on this functionality yet.

Other features we'll document soon:

- Using the logging namespace
- Setting / unsetting probes on all publics in a namespace, etc
- Other memory options (like a sliding window queue)
- Sampling transform channels
- Using log sinks
- Creating a database sink example
- Capturing streams of exceptions for post-analysis
- Low level access via write-state

## Discussion

I moved to core.async in 0.9.0 because it provides a sound
infrastructure for assembling functions in topologies to operate over
streams of events.  I've picked a simple di-graph topology to keep
things simple for interactive use, but it would only take a little
extra code to support more complex DAG-style topologies.

## Future Work

Here are some opportunities to improve the library.

### Future Tasks (Minor)

* Add higher level channel constructor support
* Add a clojure EDN file sink
* Record the stack state at a probe point 
* Add higher level targeted function tracing / collection facilities
  (e.g. trace 100 input/result vectors from function f or namespace ns)
* Add metadata so we can inspect what functions are probed
* Add a Reimann sink as probe.reimann usin the Reimann clojure client

### Major Tasks

* Deduplication.  It's easy to create multiple paths to the same sink; how do we
     handle (or do we) deduplication particularly when subscription channels
     may transform the data invalidating an = comparison?
* Complex topologies.  Right now we have a single transforming channel between
     a selector and a sink.  What if we wanted to share functionality across
     streams?  How would we specify, wire up, and control a more complex topology?
* Injest legacy logging messages - Most systems will have legacy libraries that
     use one of the Java logging systems.  Create some namespaces that
     allow for injecting these log messages into clj-probe middleware.  Ignore
     any that we inject using the log sink.  This may be non-trivial.
* Adapt the function probes to collect profile information

   	 