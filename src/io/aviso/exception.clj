(ns io.aviso.exception
  "Code to assist with presenting exceptions in pretty way."
  (import [java.lang StringBuilder StackTraceElement]
          [clojure.lang Compiler])
  (use io.aviso.ansi)
  (require [clojure
            [set :as set]
            [string :as str]]
           [io.aviso.writer :as w]))

(defn- string-length
  [^String s]
  (.length s))

;;; Obviously, this is making use of some internals of Clojure that
;;; could change at any time.

(def ^:private clojure->java
  (->> (Compiler/CHAR_MAP)
       set/map-invert
       (sort-by #(-> % first string-length))
       reverse))


(defn- match-mangled
  [^String s i]
  (->> clojure->java
       (filter (fn [[k _]] (.regionMatches s i k 0 (string-length k))))
       ;; Return the matching sequence and its single character replacement
       first))

(defn demangle
  "De-munges a Java name back to a Clojure name by converting mangled sequences, such as \"_QMARK_\"
  back into simple characters."
  [^String s]
  (let [in-length (.length s)
        result (StringBuilder. in-length)]
    (loop [i 0]
      (cond
        (>= i in-length) (.toString result)
        (= \_ (.charAt s i)) (let [[match replacement] (match-mangled s i)]
                               (.append result replacement)
                               (recur (+ i (string-length match))))
        :else (do
                (.append result (.charAt s i))
                (recur (inc i)))))))

(defn- match-keys
  "Apply the function f to all values in the map; where the result is truthy, add the key to the result."
  [m f]
  ;; (seq m) is necessary because the source is via (bean), which returns an odd implementation of map
  (reduce (fn [result [k v]] (if (f v) (conj result k) result)) [] (seq m)))

(defn- expand-exception
  [exception]
  (let [properties (bean exception)
        cause (:cause properties)
        nil-property-keys (match-keys properties nil?)
        throwable-property-keys (match-keys properties #(.isInstance Throwable %))
        remove' #(remove %2 %1)
        nested-exception (-> properties
                             (select-keys throwable-property-keys)
                             vals
                             (remove' nil?)
                             ;; Avoid infinite loop!
                             (remove' #(= % exception))
                             first)
        ;; Ignore basic properties of Throwable, any nil properties, and any properties
        ;; that are themselves Throwables
        discarded-keys (concat [:suppressed :message :localizedMessage :class :stackTrace]
                               nil-property-keys
                               throwable-property-keys)
        retained-properties (apply dissoc properties discarded-keys)]
    [{:exception  exception
      :properties retained-properties}
     nested-exception]))

(defn analyze-exception
  "Converts an exception into a seq of maps representing nested exceptions. Each map
  contains the original exception as :exception, plus a :properties map of additional properties
  on the exception that should be reported.

  The :properties map does not include any properties that are assignable to type Throwable.
  The first property of type Throwable (not necessarily the rootCause property)
  will be used as the nested exception (for the next map in the sequence).

  The final map in the sequence will have an additional value, :root, set to true."
  [^Throwable t]
  (loop [result []
         current t]
    (let [[expanded nested] (expand-exception current)]
      (if nested
        (recur (conj result expanded) nested)
        (conj result (assoc expanded :root true))))))

(defn- max-length
  "Find the maximum length of the strings in the collection."
  [coll]
  (if (empty? coll)
    0
    (apply max (map string-length coll))))

(defn- max-value-length
  [coll key]
  (max-length (map key coll)))

(defn- indent [writer spaces]
  (w/writes writer (apply str (repeat spaces \space))))

(defn- justify
  "w/writes the text, right justified within its column."
  ([writer width ^String value]
   (indent writer (- width (.length value)))
   (w/write writer value))
  ([writer width prefix ^String value suffix]
   (indent writer (- width (.length value)))
   (w/writes writer prefix value suffix)))

(defn- update-keys [m f]
  "Builds a map where f has been applied to each key in m."
  (into {} (map (fn [[k v]] [(f k) v]) m)))

(defn- convert-to-clojure
  [class-name]
  (let [[namespace-name & raw-function-ids] (str/split class-name #"\$")
        ;; Clojure adds __1234 unique ids to the ends of things, remove those.
        function-ids (map #(or (nth (first (re-seq #"([\w|.|-]+)__\d+?" %)) 1 nil) %) raw-function-ids)]
    ;; The assumption is that no real namespace or function name will contain underscores (the underscores
    ;; are name-mangled dashes).
    (->> (cons namespace-name function-ids) (map demangle))))

(defn- expand-stack-trace-element
  [^StackTraceElement element]
  (let [class-name (.getClassName element)
        method-name (.getMethodName element)
        file-name (or (.getFileName element) "")
        is-clojure? (and (.endsWith file-name ".clj")
                         (contains? #{"invoke" "doInvoke"} method-name))
        names (if is-clojure? (convert-to-clojure class-name) [])
        name (str/join "/" names)]
    {:file   file-name
     :line   (-> element .getLineNumber str)
     :class  class-name
     :method method-name
     ;; Used to calculate column width
     :name   name
     ;; Used to present compound name with last term highlighted
     :names  names}))

(def ^:private empty-stack-trace-warning "Stack trace of root exception is empty; this is likely due to a JVM optimization that can be disabled with -XX:-OmitStackTraceInFastThrow.")

(defn expand-stack-trace
  "Extracts the stack trace for an exception and returns a seq of expanded element maps:
  - :file file name
  - :line line number
  - :class Java class name
  - :method Java method name
  - :name - Fully qualified Clojure name, or blank
  - :names - Clojure name split at slashes "
  [^Throwable exception]
  (let [elements (map expand-stack-trace-element (.getStackTrace exception))]
    (when (empty? elements)
      (binding [*out* *err*]
        (println empty-stack-trace-warning)
        (flush)))
    elements))

(def ^:dynamic *fonts*
  "ANSI fonts for different elements in the formatted exception report."
  {:exception     (str bold-font red-font)
   :reset         reset-font
   :message       italic-font
   :property      bold-font
   :function-name bold-font})

(defn- write-stack-trace
  [writer exception]
  (let [elements (expand-stack-trace exception)
        file-width (max-value-length elements :file)
        line-width (max-value-length elements :line)
        name-width (max-value-length elements :name)
        class-width (max-value-length elements :class)]
    (doseq [{:keys [file line ^String name names class method]} elements]
      (indent writer (- name-width (.length name)))
      ;; There will be 0 or 2+ names (the first being the namespace)
      (when-not (empty? names)
        (doto writer
          (w/write (->> names drop-last (str/join "/")))
          (w/writes "/" (:function-name *fonts*) (last names) (:reset *fonts*))))
      (doto writer
        (w/write "  ")
        (justify file-width file)
        (w/write ":")
        (justify line-width line)
        (w/write "  ")
        (justify class-width class)
        (w/writes "." method \newline)))))


(defn write-exception
  "w/writes a formatted version of the exception to the writer.

  The *fonts* var contains ANSI definitions for how fonts are displayed; bind it to nil to remove ANSI formatting entirely."
  ([exception] (write-exception *out* exception))
  ([writer exception]
   (let [exception-font (:exception *fonts*)
         message-font (:message *fonts*)
         property-font (:property *fonts*)
         reset-font (:reset *fonts* "")
         exception-stack (->> exception
                              analyze-exception
                              (map #(assoc % :name (-> % :exception class .getName))))
         exception-column-width (max-value-length exception-stack :name)]
     (doseq [e exception-stack]
       (let [^Throwable exception (-> e :exception)
             message (.getMessage exception)]
         (justify writer exception-column-width exception-font (:name e) reset-font)
         ;; TODO: Handle no message for the exception specially
         (w/writes writer ":"
                 (if message
                   (str " " message-font message reset-font)
                   "")
                 \newline)

         (let [properties (update-keys (:properties e) name)
               prop-keys (keys properties)
               ;; Allow for the width of the exception class name, and some extra
               ;; indentation.
               prop-name-width (+ exception-column-width
                                  4
                                  (max-length prop-keys))]
           (doseq [k (sort prop-keys)]
             (justify writer prop-name-width property-font k reset-font)
             (w/writes writer ": " (get properties k) \newline))
           (if (:root e)
             (write-stack-trace writer exception))))))))

(defn format-exception
  "Formats an exception as a multi-line string using write-exception."
  [exception]
  (let [builder (StringBuilder. 2000)]
    (write-exception builder exception)
    (.toString builder)))