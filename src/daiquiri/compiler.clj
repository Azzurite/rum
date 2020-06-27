(ns daiquiri.compiler
  (:require [daiquiri.normalize :as normalize]
            [daiquiri.util :refer :all]
            [clojure.set :as set]
            [cljs.analyzer :as ana]))

(def ^:private primitive-types
  "The set of primitive types that can be handled by React."
  #{'js 'clj-nil 'js/React.Element
    'number 'string 'boolean 'symbol
    'array 'object 'function})

(defn- primitive-type?
  "Return true if `tag` is a primitive type that can be handled by
  React, otherwise false. "
  [tags]
  (and (not (empty? tags))
       (set/subset? tags primitive-types)))

(defn infer-tag
  "Infer the tag of `form` using `env`."
  [env form]
  (when env
    (when-let [tags (ana/infer-tag env (ana/no-warn (ana/analyze env form)))]
      (if (set? tags) tags (set [tags])))))

(declare to-js to-js-map)

(defn fragment?
  "Returns true if `tag` is the fragment tag \"*\" or \"<>\", otherwise false."
  [tag]
  (or (= (name tag) "*")
      (= (name tag) "<>")))

(defmulti compile-attr (fn [name value] name))

(defmethod compile-attr :class [_ value]
  (cond
    (or (nil? value)
        (keyword? value)
        (string? value))
    value
    (and (or (sequential? value)
             (set? value))
         (every? string? value))
    (join-classes value)
    :else `(daiquiri.util/join-classes ~value)))

(defmethod compile-attr :style [_ value]
  (let [value (camel-case-keys value)]
    (if (map? value)
      (to-js-map value)
      `(daiquiri.interpreter/attributes ~value))))

(defmethod compile-attr :default [_ value]
  (to-js value))

(defn compile-attrs
  "Compile a HTML attribute map."
  [attrs]
  (when (seq attrs)
    (->> (seq attrs)
         (reduce (fn [attrs [name value]]
                   (assoc attrs name (compile-attr name value)))
                 nil)
         html-to-dom-attrs
         to-js-map)))

(defn compile-merge-attrs [attrs-1 attrs-2]
  (let [empty-attrs? #(or (nil? %1) (and (map? %1) (empty? %1)))]
    (cond
      (and (empty-attrs? attrs-1)
           (empty-attrs? attrs-2))
      nil
      (empty-attrs? attrs-1)
      `(daiquiri.interpreter/attributes ~attrs-2)
      (empty-attrs? attrs-2)
      `(daiquiri.interpreter/attributes ~attrs-1)
      (and (map? attrs-1)
           (map? attrs-2))
      (normalize/merge-with-class attrs-1 attrs-2)
      :else `(daiquiri.interpreter/attributes
              (daiquiri.normalize/merge-with-class ~attrs-1 ~attrs-2)))))

(defn- compile-tag
  "Replace fragment syntax (`:*` or `:<>`) by 'React.Fragment, otherwise the
  name of the tag"
  [tag]
  (if (fragment? tag)
    'daiquiri.core/fragment
    (name tag)))

(declare compile-react)

(defn compile-react-element
  "Render an element vector as a HTML element."
  [element env]
  (let [[tag attrs content] (normalize/element element)]
    `(daiquiri.core/create-element
      ~(compile-tag tag)
      ~(when (seq attrs)
         (compile-attrs attrs))
      ~(when (seq content)
         `(cljs.core/array ~@(compile-react content env))))))

(defn- unevaluated?
  "True if the expression has not been evaluated."
  [expr]
  (or (symbol? expr)
      (and (seq? expr)
           (not= (first expr) `quote))))

(defmacro interpret-maybe
  "Macro that wraps `expr` with a call to
  `daiquiri.interpreter/interpret` if the inferred return type is not a
  primitive React type."
  [expr]
  (if (primitive-type? (infer-tag &env expr))
    expr `(daiquiri.interpreter/interpret ~expr)))

(defn- form-name
  "Get the name of the supplied form."
  [form]
  (if (and (seq? form) (symbol? (first form)))
    (name (first form))))

(declare compile-html)

(defmulti compile-form
  "Pre-compile certain standard forms, where possible."
  {:private true}
  (fn [form env] (form-name form)))

(defmethod compile-form "case"
  [[_ v & cases] env]
  `(case ~v
     ~@(doall (mapcat
               (fn [[test hiccup]]
                 (if hiccup
                   [test (compile-html hiccup env)]
                   [(compile-html test env)]))
               (partition-all 2 cases)))))

(defmethod compile-form "cond"
  [[_ & clauses] env]
  `(cond ~@(mapcat
            (fn [[check expr]] [check (compile-html expr env)])
            (partition 2 clauses))))

(defmethod compile-form "condp"
  [[_ f v & cases] env]
  `(condp ~f ~v
     ~@(doall (mapcat
               (fn [[test hiccup]]
                 (if hiccup
                   [test (compile-html hiccup env)]
                   [(compile-html test env)]))
               (partition-all 2 cases)))))

(defmethod compile-form "do"
  [[_ & forms] env]
  `(do ~@(butlast forms) ~(compile-html (last forms) env)))

(defmethod compile-form "let"
  [[_ bindings & body] env]
  `(let ~bindings ~@(butlast body) ~(compile-html (last body) env)))

(defmethod compile-form "let*"
  [[_ bindings & body] env]
  `(let* ~bindings ~@(butlast body) ~(compile-html (last body) env)))

(defmethod compile-form "letfn*"
  [[_ bindings & body] env]
  `(letfn* ~bindings ~@(butlast body) ~(compile-html (last body) env)))

(defmethod compile-form "for"
  [[_ bindings body] env]
  `(~'into-array (for ~bindings ~(compile-html body env))))

(defmethod compile-form "if"
  [[_ condition & body] env]
  `(if ~condition ~@(for [x body] (compile-html x env))))

(defmethod compile-form "if-not"
  [[_ bindings & body] env]
  `(if-not ~bindings ~@(doall (for [x body] (compile-html x env)))))

(defmethod compile-form "if-some"
  [[_ bindings & body] env]
  `(if-some ~bindings ~@(doall (for [x body] (compile-html x env)))))

(defmethod compile-form "when"
  [[_ bindings & body] env]
  `(when ~bindings ~@(doall (for [x body] (compile-html x env)))))

(defmethod compile-form "when-not"
  [[_ bindings & body] env]
  `(when-not ~bindings ~@(doall (for [x body] (compile-html x env)))))

(defmethod compile-form "when-some"
  [[_ bindings & body] env]
  `(when-some ~bindings ~@(butlast body) ~(compile-html (last body) env)))

(defmethod compile-form :default
  [expr env]
  (if (:inline (meta expr))
    expr `(interpret-maybe ~expr)))

(defn- not-hint?
  "True if x is not hinted to be the supplied type."
  [x type]
  (if-let [hint (-> x meta :tag)]
    (not (isa? (eval hint) type))))

(defn- hint?
  "True if x is hinted to be the supplied type."
  [x type]
  (if-let [hint (-> x meta :tag)]
    (isa? (eval hint) type)))

(defn- literal?
  "True if x is a literal value that can be rendered as-is."
  [x]
  (and (not (unevaluated? x))
       (or (not (or (vector? x) (map? x)))
           (every? literal? x))))

(defn- not-implicit-map?
  "True if we can infer that x is not a map."
  [x]
  (or (= (form-name x) "for")
      (not (unevaluated? x))
      (not-hint? x java.util.Map)))

(defn- attrs-hint?
  "True if x has :attrs metadata. Treat x as a implicit map"
  [x]
  (-> x meta :attrs))

(defn- inline-hint?
  "True if x has :inline metadata. Treat x as a implicit map"
  [x]
  (-> x meta :inline))

(defn- element-compile-strategy
  "Returns the compilation strategy to use for a given element."
  [[tag attrs & content :as element] env]
  (cond
    ;; e.g. [:span "foo"]
    (every? literal? element)
    ::all-literal

    ;; e.g. [:span {} x]
    (and (literal? tag) (map? attrs))
    ::literal-tag-and-attributes

    ;; e.g. [:span ^String x]
    (and (literal? tag) (not-implicit-map? attrs))
    ::literal-tag-and-no-attributes

    ;; e.g. [:span ^:attrs y] or [:span (attrs)], return type of `attrs` is a map
    (and (literal? tag)
         (or (= '#{cljs.core/IMap} (infer-tag env attrs))
             (attrs-hint? attrs)))
    ::literal-tag-and-hinted-attributes

    ;; e.g. [:span ^:inline (y)]
    (and (literal? tag) (inline-hint? attrs))
    ::literal-tag-and-inline-content

    ;; ; e.g. [:span x]
    (literal? tag)
    ::literal-tag

    ;; e.g. [x]
    :else
    ::default))

(declare compile-html)

(defmulti compile-element
  "Returns an unevaluated form that will render the supplied vector as a HTML
          element."
  {:private true}
  #'element-compile-strategy)

(defmethod compile-element ::all-literal
  [element env]
  (compile-react-element (eval element) env))

(defmethod compile-element ::literal-tag-and-attributes
  [[tag attrs & content] env]
  (let [[tag attrs _] (normalize/element [tag attrs])]
    `(daiquiri.core/create-element
      ~(compile-tag tag)
      ~(compile-attrs attrs)
      (cljs.core/array ~@(map #(compile-html % env) content)))))

(defmethod compile-element ::literal-tag-and-no-attributes
  [[tag & content] env]
  (compile-element (apply vector tag {} content) env))

(defmethod compile-element ::literal-tag-and-inline-content
  [[tag & content] env]
  (compile-element (apply vector tag {} content) env))

(defmethod compile-element ::literal-tag-and-hinted-attributes
  [[tag attrs & content] env]
  (let [[tag tag-attrs _] (normalize/element [tag])
        attrs-sym (gensym "attrs")]
    `(let [~attrs-sym ~attrs]
       (daiquiri.core/create-element
        ~(compile-tag tag)
        ~(compile-merge-attrs tag-attrs attrs-sym)
        ~(when-not (empty? content)
           `(cljs.core/array ~@(mapv #(compile-html % env) content)))))))

(defmethod compile-element ::literal-tag
  [[tag attrs & content] env]
  (let [[tag tag-attrs _] (normalize/element [tag])
        attrs-sym (gensym "attrs")]
    `(let [~attrs-sym ~attrs]
       (daiquiri.core/create-element
        ~(compile-tag tag)
        (if (map? ~attrs-sym)
          ~(compile-merge-attrs tag-attrs attrs-sym)
          ~(compile-attrs tag-attrs))
        (if (map? ~attrs-sym)
          ~(when-not (empty? content)
             `(cljs.core/array ~@(mapv #(compile-html % env) content)))
          ~(when attrs
             `(cljs.core/array ~@(mapv #(compile-html % env) (cons attrs-sym content)))))))))

(defmethod compile-element :default
  [element env]
  `(daiquiri.interpreter/interpret
    [~(first element)
     ~@(for [x (rest element)]
         (if (vector? x)
           (compile-element x env)
           x))]))

(defn compile-html
  "Pre-compile data structures into HTML where possible."
  ([content]
   (compile-html content nil))
  ([content env]
   (cond
     (vector? content) (compile-element content env)
     (literal? content) content
     (hint? content String) content
     (hint? content Number) content
     :else (compile-form content env))))

(defn compile-react [v env]
  (cond
    (vector? v) (if (element? v)
                  (compile-react-element v env)
                  (compile-react (seq v) env))
    (seq? v) (map #(compile-react % env) v)
    :else v))

(defn- js-obj [m]
  (let [key-strs (mapv to-js (keys m))
        kvs-str (->> (mapv #(-> (str \' % "':~{}")) key-strs)
                     (interpose ",")
                     (apply str))]
    (vary-meta
     (list* 'js* (str "{" kvs-str "}") (mapv to-js (vals m)))
     assoc :tag 'object)))

(defn- to-js-map
  "Convert a map into a JavaScript object."
  [m]
  (if (every? literal? (keys m))
    (js-obj m)
    `(daiquiri.interpreter/attributes ~m)))

(defn- to-js-array
  "Convert a vector into a JavaScript array."
  [x]
  (apply list 'cljs.core/array (mapv to-js x)))

(defn to-js [x]
  (cond
    (keyword? x) (name x)
    (map? x) (to-js-map x)
    (vector? x) (to-js-array x)
    :else x))
