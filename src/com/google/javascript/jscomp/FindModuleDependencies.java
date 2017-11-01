/*
 * Copyright 2017 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.javascript.jscomp.CompilerInput.ModuleType;
import com.google.javascript.jscomp.Es6RewriteModules.FindGoogProvideOrGoogModule;
import com.google.javascript.jscomp.deps.ModuleLoader;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.Map;

/**
 * Find and update any direct dependencies of an input. Used to walk the dependency graph and
 * support a strict depth-first dependency ordering. Marks an input as providing its module name.
 *
 * <p>Discovers dependencies from:
 * <ul>
 *   <li> goog.require calls
 *   <li> ES6 import statements
 *   <li> CommonJS require statements
 * </ul>
 *
 * <p>The order of dependency references is preserved so that a deterministic depth-first ordering
 * can be achieved.
 *
 * @author chadkillingsworth@gmail.com (Chad Killingsworth)
 */
public class FindModuleDependencies implements NodeTraversal.ScopedCallback {
  private final AbstractCompiler compiler;
  private final boolean supportsEs6Modules;
  private final boolean supportsCommonJsModules;
  private ModuleType moduleType = ModuleType.NONE;
  private Scope dynamicImportScope = null;
  private final Map<String, String> inputPathByWebpackId;

  FindModuleDependencies(
      AbstractCompiler compiler,
      boolean supportsEs6Modules,
      boolean supportsCommonJsModules,
      Map<String, String> inputPathByWebpackId) {
    this.compiler = compiler;
    this.supportsEs6Modules = supportsEs6Modules;
    this.supportsCommonJsModules = supportsCommonJsModules;
    this.inputPathByWebpackId = inputPathByWebpackId;
  }

  public void process(Node root) {
    checkArgument(root.isScript());
    if (FindModuleDependencies.isEs6ModuleRoot(root)) {
      moduleType = ModuleType.ES6;
    }
    CompilerInput input = compiler.getInput(root.getInputId());

    // The "goog" namespace isn't always specifically required.
    // The deps parser will pick up any access to a `goog.foo()` call
    // and add "goog" as a dependency. If "goog" is a dependency of the
    // file we add it here to the ordered requires so that it's always
    // first.
    if (input.getRequires().contains("goog")) {
      input.addOrderedRequire("goog");
    }

    NodeTraversal.traverseEs6(compiler, root, this);

    if (moduleType == ModuleType.NONE && inputPathByWebpackId != null
        && inputPathByWebpackId.containsValue(input.getPath().toString())) {
      moduleType = ModuleType.IMPORTED_SCRIPT;
    }

    input.addProvide(input.getPath().toModuleName());
    input.setJsModuleType(moduleType);
    input.setHasFullParseDependencyInfo(true);
  }

  @Override
  public boolean shouldTraverse(NodeTraversal t, Node n, Node parent) {
    if (supportsCommonJsModules
        && n.isFunction()
        && ProcessCommonJSModules.isCommonJsDynamicImportCallback(n,
            compiler.getOptions().moduleResolutionMode)) {
      if (dynamicImportScope == null) {
        dynamicImportScope = t.getScope();
      }
    }

    return true;
  }

  @Override
  public void visit(NodeTraversal t, Node n, Node parent) {
    ModuleLoader.ResolutionMode resolutionMode = compiler.getOptions().moduleResolutionMode;
    if (parent == null
        || NodeUtil.isControlStructure(parent)
        || NodeUtil.isStatementBlock(parent)) {
      if (n.isExprResult()) {
        Node maybeGetProp = n.getFirstFirstChild();
        if (maybeGetProp != null
            && (maybeGetProp.matchesQualifiedName("goog.provide")
            || maybeGetProp.matchesQualifiedName("goog.module"))) {
          moduleType = ModuleType.GOOG;
          return;
        }
      }
    }

    if (supportsEs6Modules && n.isExport()) {
      moduleType = ModuleType.ES6;

    } else if (supportsEs6Modules && n.isImport()) {
      moduleType = ModuleType.ES6;
      String moduleName;
      String importName = n.getLastChild().getString();
      boolean isNamespaceImport = importName.startsWith("goog:");
      if (isNamespaceImport) {
        // Allow importing Closure namespace objects (e.g. from goog.provide or goog.module) as
        //   import ... from 'goog:my.ns.Object'.
        // These are rewritten to plain namespace object accesses.
        moduleName = importName.substring("goog:".length());
      } else {
        ModuleLoader.ModulePath modulePath =
            t.getInput()
                .getPath()
                .resolveJsModule(importName, n.getSourceFileName(), n.getLineno(), n.getCharno());
        if (modulePath == null) {
          // The module loader issues an error
          // Fall back to assuming the module is a file path
          modulePath = t.getInput().getPath().resolveModuleAsPath(importName);
        }
        moduleName = modulePath.toModuleName();
      }
      if (moduleName.startsWith("goog.")) {
        t.getInput().addOrderedRequire("goog");
      }
      t.getInput().addOrderedRequire(moduleName);
    } else if (supportsCommonJsModules) {
      if (moduleType != ModuleType.GOOG
          && ProcessCommonJSModules.isCommonJsExport(t, n, resolutionMode)) {
        moduleType = ModuleType.COMMONJS;
      } else if (ProcessCommonJSModules.isCommonJsImport(n, resolutionMode)) {
        String path = ProcessCommonJSModules.getCommonJsImportPath(n, resolutionMode);

        ModuleLoader.ModulePath modulePath =
            t.getInput()
                .getPath()
                .resolveJsModule(path, n.getSourceFileName(), n.getLineno(), n.getCharno());

        if (modulePath != null) {
          if (dynamicImportScope != null
              || (n.getParent().isCall()
                  && n.getPrevious() != null
                  && n.getPrevious().isGetProp()
                  && n.getPrevious().getFirstChild().isCall()
                  && n.getPrevious().getFirstFirstChild().isQualifiedName()
                  && n.getPrevious().getFirstFirstChild().matchesQualifiedName("__webpack_require__.e"))) {
            t.getInput().addDynamicRequire(modulePath.toModuleName());
          } else {
            t.getInput().addOrderedRequire(modulePath.toModuleName());
          }
        }
      }

      // TODO(ChadKillingsworth) add require.ensure support
    }

    if (parent != null
        && (parent.isExprResult() || !t.inGlobalHoistScope())
        && n.isCall()
        && n.getFirstChild().matchesQualifiedName("goog.require")
        && n.getSecondChild() != null
        && n.getSecondChild().isString()) {
      String namespace = n.getSecondChild().getString();
      if (namespace.startsWith("goog.")) {
        t.getInput().addOrderedRequire("goog");
      }
      t.getInput().addOrderedRequire(namespace);
    }
  }

  @Override
  public void enterScope(NodeTraversal t) {}

  @Override
  public void exitScope(NodeTraversal t) {
    if (t.getScope() == dynamicImportScope) {
      dynamicImportScope = null;
    }
  }

  /** Return whether or not the given script node represents an ES6 module file. */
  public static boolean isEs6ModuleRoot(Node scriptNode) {
    checkArgument(scriptNode.isScript());
    if (scriptNode.getBooleanProp(Node.GOOG_MODULE)) {
      return false;
    }
    return scriptNode.hasChildren() && scriptNode.getFirstChild().isModuleBody();
  }
}
