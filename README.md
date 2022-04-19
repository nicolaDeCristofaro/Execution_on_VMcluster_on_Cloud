# Execution on a cluster of Virtual Machines on Cloud
A typical situation is the execution of a program (usally a computation-intensive algorithm) on a cluster of virtual machines on Cloud. In this way it is possible to take advantage of parallelization to obtain better performance.

In this scenario, the programmer would have to manually do the following operations:
- configure and launch the cluster of Virtual Machines
- upload the program to be executed on each instance of the cluster
- log in on each instance of the cluster
- run the program in parallel on each instance of the cluster
- deallocate all the resources used

It can be seen that such a procedure could be very tedious. For this reason, through the Java SDK made available by the various cloud providers, I have created this project that allows you to automatically perform all the operations described above.

Also, creating and killing the VM on every run can take a lot of time. To optimize times, the programmer was given the choice with the setting of a "persistence" parameter.

- If <b>"persistence = true"</b> then before creating a VM, you check if there is an already allocated VM that has the same desired characteristics, and use that for execution. At the end the latter is not finished, but is ready for a subsequent execution.
- If <b>"persistence = false"</b> then an existing VM is used if it has the desired characteristics, as before, but eventually the VM is terminated.

<b>** As an example a JAVA program has been run, but you can see how it is possible to run any type of program, just install the dependencies necessary to run the program on the VM in the initial boot script.</b>

<b>** Remember to set your credentials before testing the program </b>

<b>** Java SDK v1.x used...planning to migrate to version 2.x</b>
