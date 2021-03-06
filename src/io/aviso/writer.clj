(ns io.aviso.writer
  "The StringWriter protocol is used as the target of any written output."
  (:import
    [java.io Writer]))

(defprotocol StringWriter
  "May receive strings, which are printed, or stored.

  Writer is extended onto java.lang.Appendable, a common interface implemented by both PrintWriter and StringBuilder (among
  many others)"

  (write-string [this string] "Writes the string to the Writer.")
  (flush-writer [this] "Flushes output to the Writer, we appropriate."))

(extend-protocol StringWriter
  StringBuilder
  (write-string [this ^CharSequence string] (.append this string))
  (flush-writer [this] nil))

(extend-protocol StringWriter
  Writer
  (write-string [this ^CharSequence string] (.print this string))
  (flush-writer [this] (.flush this)))

(def ^:private endline
  "End-of-line terminator, platform specific."
  (System/getProperty "line.separator"))

(defn write
  "Constructs a string from the values (with no seperator) and writes the string to the StringWriter.

  This is used to get around the fact that protocols do not support varadic parameters."
  ([writer value]
   (write-string writer (str value)))
  ([writer value & values]
   (write writer value)
   (doseq [value values]
          (write writer value))))

(defn writeln
  "Constructs a string from the values (with no seperator) and writes the string to the StringWriter,
  followed by an end-of-line terminator."
  ([writer]
   (write-string writer endline)
   (flush-writer writer))
  ([writer & values]
   (apply write writer values)
   (writeln writer)))

(defn writef
  "Writes formatted data."
  [writer fmt & values]
  (write-string writer (apply format fmt values)))

(defn into-string
  "Creates a StringBuilder and passes that as the first parameter to the function, along with the other parameters.

  Returns the value of the StringBuilder after invoking the function."
  [f & params]
  (let [sb (StringBuilder. 2000)]
    (apply f sb params)
    (.toString sb)))

