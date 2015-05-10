# TinyAsync

[![Build Status](https://travis-ci.org/udoprog/tiny-async-java.svg?branch=master)](https://travis-ci.org/udoprog/tiny-async-java)
[![Coverage Status](https://coveralls.io/repos/udoprog/tiny-async-java/badge.svg?branch=master)](https://coveralls.io/r/udoprog/tiny-async-java?branch=master)

A tiny asynchronous library for Java.

Writing multithreaded code is hard, tiny async tries to make it easier by providing simple abstractions for executing and manipulating computations through a clean API abstraction.

# Why TinyAsync

In short; everything is tucked behind an API, and some functionality has been moved into the future itself to allow for cleaner code.

Google Guava provides the [Futures helper class](http://docs.guava-libraries.googlecode.com/git/javadoc/com/google/common/util/concurrent/Futures.html) that allows the user of the library to do interesting things with their futures.

The issue I have with this pattern is that it is a leaky abstraction.

The use of for example ```Futures#transform(ListenableFuture<I> input, AsyncFunction<? super I,? extends O> function)``` directly in your code means that the specific code will always use a direct executor.
Since most of the future API is provided statically, there are no way to configure the helpers default behaviour, users are left to wrap Guava specifically for their application if they want to accomplish this.

TinyAsync attempts to address by allowing these aspects of the framework to be configured, see [AsyncSetup.java](tiny-async-core/src/example/java/eu/toolchain/examples/AsyncSetup.java).

The benefits are;

* Your application only have to interact with TinyAsync through the  [AsyncFramework](tiny-async-api/src/main/java/eu/toolchain/async/AsyncFramework.java) interface, which is part of the API package. This allows for decoupling between implementation and API.
* TinyAsync can be configured with sensible defaults for _your_ application, and these can be enjoyed by all components without having to rewrite their code.
* Coupled with a dependency injection framework, your component does not have to pull in the actual implementation of their futures, everything is behind a clean API.

For an overview of the library, check out the
[API](tiny-async-api/src/main/java/eu/toolchain/async) and the [Usage](#usage)
section below.

# Setup

Add tiny-async-core as a dependency to your project, and tiny-async-api as
a dependency to your public API.

```
<dependency>
  <groupId>eu.toolchain.async</groupId>
  <artifactId>tiny-async</artifactId>
  <version>1.0.3</version>
</dependency>
```

After that, the first step is to instantiate the framework.

See [AsyncSetup.java](tiny-async-core/src/example/java/eu/toolchain/examples/AsyncSetup.java)
for an example of how to do this.

# Usage

The following section contains documentation on how to use TinyAsync.

## Building futures from scratch

The following methods are provided on ```AsyncFramework``` to build new futures.

* ```ResolvableFuture<T> AsyncFramework#future()```
* ```AsyncFuture<T> AsyncFramework#call(Callable<T>)```
* ```AsyncFuture<T> AsyncFramework#call(Callable<T>, ExecutorService)```
* ```AsyncFuture<T> AsyncFramework#call(Callable<T>, ExecutorService, ResolvableFuture<T>)```
* ```AsyncFuture<T> AsyncFramework#resolved(T)```
* ```AsyncFuture<T> AsyncFramework#failed(Throwable)```
* ```AsyncFuture<T> AsyncFramework#cancelled()```

The first kind of method returns a ```ResolvableFuture<T>``` instance. This is typically used when integrating with other async framework and has direct access to a ```#resolve(T)``` method that will resolve the future.

The methods that take a ```Callable<T>``` builds a new future that will be resolved when the given callable has returned.

The last kind of methods are the ones building futures which have already been either resolved, failed, or cancelled.
These types of methods are good for returning early from methods that only returns a future.
An example is if a method throws a checked exception, and you want this to be returned as a future, you can use ```AsyncFramework#failed(Throwable)```.

See examples:

* [blocking example](tiny-async-core/src/example/java/eu/toolchain/examples/AsyncBlockingExample.java)
* [static results example](tiny-async-core/src/example/java/eu/toolchain/examples/AsyncStaticResultsExample.java)
* [manually resolving a future](tiny-async-core/src/example/java/eu/toolchain/examples/AsyncManualResolvingExample.java)

## Subscribing to changes

The following methods allow you to subscribe to interesting changes on the
futures.

* ```AsyncFuture<T> AsyncFuture#on(FutureDone<T>)```
* ```AsyncFuture<T> AsyncFuture#on(FutureFinished)```
* ```AsyncFuture<T> AsyncFuture#on(FutureCancelled)```
* ```AsyncFuture<T> AsyncFuture#onAny(FutureDone<Object>)```

If the event handlers throw an exception, this is intepreted as an 'internal'
error, and will be reported as such in the provided ```AsyncCaller```.

There is no other reasonable way to handle this circumstance, and you are
expected to avoid throwing exceptions here (or implement a sane AsyncCaller
handle).

See examples:

* [subscribe example](tiny-async-core/src/example/java/eu/toolchain/examples/AsyncSubscribeExample.java)

## Blocking until a result is available

This is the implemented behaviour of ```java.util.concurrent.Future#get()```.

Blocking isn't a terribly interesting async behaviour, and frankly it is beyond
me why ```java.util.concurrent.Future``` is so poorly designed.

You should mostly rely on [Subscribing to events](#subscribing-to-events),
transformers and collectors.

Note: most of these examples make use of ```#get()```, mainly because it is
convenient in this contrived context.

See examples:

* [blocking example](tiny-async-core/src/example/java/eu/toolchain/examples/AsyncBlockingExample.java)

## Transforming results

Transformations is a lightweight way of forward potential results or failures.

They allow you to take a value A, and convert it to a value B.

They also allows to take a falied future, and convert it into a value B.

* ```AsyncFuture<C> AsyncFuture#transform(Transform<T, C>)```
* ```AsyncFuture<C> AsyncFuture#transform(LazyTransform<T, C>)```
* ```AsyncFuture<C> AsyncFuture#error(Transform<Throwable, C>)```
* ```AsyncFuture<C> AsyncFuture#error(LazyTransform<Throwable, C>)```

See examples:

* [transform example](tiny-async-core/src/example/java/eu/toolchain/examples/AsyncTransformExample.java)

## Collecting Many Results

When you have a collection of asynchronous operations that needs to be
'collected', the process is called collecting.

* ```AsyncFuture<Collection<T>> TinyAsync#collect(Collection<AsyncFuture<C>>)```
* ```AsyncFuture<Void> TinyAsync#collectAndIgnore(Collection<AsyncFuture<C>>)```
* ```AsyncFuture<T> TinyAsync#collect(Collection<AsyncFuture<C>>, Collector<C, T>)```
* ```AsyncFuture<T> TinyAsync#collect(Collection<AsyncFuture<C>>, StreamCollector<C, T>)```

The first type ```Collector``` collects the result from all the futures and
executes the reduction.
This has the benefit of not requiring to synchronize.

The second type ```StreamCollector``` is called with the results as they
resolve or fail.
This has the benefit of using less memory overall, since the framework does not
have to maintain all the seen reults so far, but requires synchronization from
the user.

See examples:

* [collector example](tiny-async-core/src/example/java/eu/toolchain/examples/AsyncCollectorExample.java)
* [stream collector example](tiny-async-core/src/example/java/eu/toolchain/examples/AsyncStreamCollectorExample.java)
