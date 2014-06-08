I'd like to write my functions without any fuss, and then be able to dynamically turn
probing on/off at the namespace level.

I would provide some kind of configuration that would specify which namespaces to probe and
corresponding options, like:

+ Whether to probe public fns, private fns, or both
+ An integer *level* for the namespace that dictates whether or not probing should be activated or not
+ A set of sinks for the probes to stream to for that namespace

Config accepts the following:

+ **namespace**: *[symbol, required]* Namespace to install probe-fns
+ **public**: *[bool, optional, true] *Install probe-fns on public functions?
+ **private**: *[bool, optional, false]* Install probe-fns on private functions?
+ **tags**: *[set|vector, optional, []]* Additional tags to add to vars in this namespace
+ **suppress-results?:** *[bool, optional, false]* Results from some functions are large. It can be useful to stamp them out.
