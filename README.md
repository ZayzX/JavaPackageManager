
# 📦 JPM — Java Package Manager

A lightweight Java package manager inspired by npm and Maven, built in Java.  
It allows you to initialize projects, manage dependencies, install libraries from Maven Central, handle transitive dependencies, resolve version conflicts, and run scripts.

---

# 📂 Project Structure

project/  
│── jpm.java  
│── package.json  
│── config.json  
│── libs/

---

# 📄 package.json Example

{  
&nbsp;&nbsp;&nbsp;"name": "my-project",  
&nbsp;&nbsp;&nbsp;"version": "1.0.0",  
&nbsp;&nbsp;&nbsp;"main": "Main",  
&nbsp;&nbsp;&nbsp;"scripts": {  
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"start": "java -jar my-app.jar"  
&nbsp;&nbsp;&nbsp;},  
&nbsp;&nbsp;&nbsp;"dependencies": {  
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"com.google.code.gson:gson": "2.10.1",  
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;"org.apache.commons:commons-lang3": "3.14.0"  
&nbsp;&nbsp;&nbsp;}  
}  

---

# ⚡ Commands

## init
jpm init

## run
jpm run start

## install
jpm install

## install package
jpm install gson

## update
jpm update gson

## update all
jpm update-all

## search
jpm search gson

## info
jpm info gson

## tree
jpm tree

## doctor
jpm doctor

## clean
jpm clean

---

# 🧠 Dependency System

- Transitive dependency resolution
- Conflict resolution (latest wins)
- Circular dependency detection

---

# 🌐 Maven

Uses:
https://repo1.maven.org/maven2/

---

