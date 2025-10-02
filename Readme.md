Devoxx Belgium 2025 Loom Lab
============================


You can find the slides for this lab here: https://speakerdeck.com/josepaumard/devoxx-be-2025-loom-lab

## Introduction

The Loom project already brought two major features:
- Virtual Threads in JDK 21,
- Scope Value in JDK 25.

Another feature is still being developed: the Structured Concurrency API, available as a preview feature. This lab focuses on Structured Concurrency. It uses virtual threads transparently. 

It consists in the refactoring of a server application. You will start with an initial version that suffers from many problems. 

This lab takes you through a step by step refactoring, to parallelize the many requests it does, and to get a better latency and a more readable code.

You can follow it, or jump right to the step you would like to work on, using the different branches and labels of this repository. 

## Prerequisites

### JDK 25 Distribution

The features showcased in this lab are available in [JDK 25](https://openjdk.org/projects/jdk/25/) that you can download here: https://jdk.java.net/25/. So you need to download this version, and install it in your IDE. 

This version contains the version of the Structured Concurrency API that this lab is using. It differs from the version from JDK 24, so you need this version.  

### A Working Maven Installation

The application you will be working on is using the Helidon server (https://helidon.io/), as a Maven dependency. You do not need to download anything, Maven can take care of that for you. 

## Working on the Lab

All the instructions for the lab are in the file [DevoxxBE-Loom-Lab.md](Devoxx-BE-Loom-Lab.md), in this directory. Once you have the correct JDK distribution you need and configured your project, you can start working on it. 

## References

JDK 25 distribution: https://jdk.java.net/25/
The Helidon page: https://helidon.io/

- JEP 444 Virtual Threads without Pinning: https://openjdk.org/jeps/444
- JEP 506 Scoped Values: https://openjdk.org/jeps/506
- JEP 491 Synchronize Virtual Threads without Pinning: https://openjdk.org/jeps/491
- JEP 505 Structured Concurrency (Fifth Preview): https://openjdk.org/jeps/505

You may also be interested in the upcoming preview of the Structured Concurrency API : 
- JEP 525 Structured Concurrency (Sixth Preview): https://openjdk.org/jeps/525
