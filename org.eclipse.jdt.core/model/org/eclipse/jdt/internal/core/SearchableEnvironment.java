/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.core;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.CharOperation;
import org.eclipse.jdt.core.search.*;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.ITypeNameRequestor;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.internal.codeassist.ISearchRequestor;
import org.eclipse.jdt.internal.codeassist.ISearchableNameEnvironment;
import org.eclipse.jdt.internal.compiler.env.AccessRestriction;
import org.eclipse.jdt.internal.compiler.env.IBinaryType;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.IConstants;
import org.eclipse.jdt.internal.compiler.env.ISourceType;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;

/**
 *	This class provides a <code>SearchableBuilderEnvironment</code> for code assist which
 *	uses the Java model as a search tool.  
 */
public class SearchableEnvironment
	implements ISearchableNameEnvironment, IJavaSearchConstants {
	
	public NameLookup nameLookup;
	protected ICompilationUnit unitToSkip;

	protected JavaProject project;
	protected IJavaSearchScope searchScope;
	
	protected boolean checkAccessRestrictions;

	/**
	 * Creates a SearchableEnvironment on the given project
	 */
	public SearchableEnvironment(JavaProject project, org.eclipse.jdt.core.ICompilationUnit[] workingCopies) throws JavaModelException {
		this.project = project;
		this.checkAccessRestrictions = !JavaCore.IGNORE.equals(project.getOption(JavaCore.COMPILER_PB_FORBIDDEN_REFERENCE, true));
		this.nameLookup = project.newNameLookup(workingCopies);
		// Create search scope with visible entry on the project's classpath
		this.searchScope = SearchEngine.createJavaSearchScope(this.nameLookup.packageFragmentRoots);
	}

	/**
	 * Creates a SearchableEnvironment on the given project
	 */
	public SearchableEnvironment(JavaProject project, WorkingCopyOwner owner) throws JavaModelException {
		this.project = project;
		this.checkAccessRestrictions = !JavaCore.IGNORE.equals(project.getOption(JavaCore.COMPILER_PB_FORBIDDEN_REFERENCE, true));
		this.nameLookup = project.newNameLookup(owner);

		// Create search scope with visible entry on the project's classpath
		this.searchScope = SearchEngine.createJavaSearchScope(this.nameLookup.packageFragmentRoots);
	}

	/**
	 * Returns the given type in the the given package if it exists,
	 * otherwise <code>null</code>.
	 */
	protected NameEnvironmentAnswer find(String typeName, String packageName) {
		if (packageName == null)
			packageName = IPackageFragment.DEFAULT_PACKAGE_NAME;
		IType type =
			this.nameLookup.findType(
				typeName,
				packageName,
				false,
				NameLookup.ACCEPT_CLASSES | NameLookup.ACCEPT_INTERFACES);
		if (type != null) {
			boolean isBinary = type instanceof BinaryType;
			
			// determine associated access restriction
			AccessRestriction accessRestriction = null;
			
			if (this.checkAccessRestrictions && (isBinary || !type.getJavaProject().equals(this.project))) {
				PackageFragmentRoot root = (PackageFragmentRoot)type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
				ClasspathEntry entry = (ClasspathEntry) this.nameLookup.rootToResolvedEntries.get(root);
				if (entry != null) { // reverse map always contains resolved CP entry
					accessRestriction = entry.getImportRestriction();
					if (accessRestriction != null) {
						// TODO (philippe) improve char[] <-> String conversions to avoid performing them on the fly
						char[][] packageChars = CharOperation.splitOn('.', packageName.toCharArray());
						char[] typeChars = typeName.toCharArray();
						accessRestriction = accessRestriction.getViolatedRestriction(CharOperation.concatWith(packageChars, typeChars, '/'), null);
					}
				}
			}
			
			// construct name env answer
			if (isBinary) { // BinaryType
				try {
					return new NameEnvironmentAnswer((IBinaryType) ((BinaryType) type).getElementInfo(), accessRestriction);
				} catch (JavaModelException npe) {
					return null;
				}
			} else { //SourceType
				try {
					// retrieve the requested type
					SourceTypeElementInfo sourceType = (SourceTypeElementInfo)((SourceType)type).getElementInfo();
					ISourceType topLevelType = sourceType;
					while (topLevelType.getEnclosingType() != null) {
						topLevelType = topLevelType.getEnclosingType();
					}
					// find all siblings (other types declared in same unit, since may be used for name resolution)
					IType[] types = sourceType.getHandle().getCompilationUnit().getTypes();
					ISourceType[] sourceTypes = new ISourceType[types.length];

					// in the resulting collection, ensure the requested type is the first one
					sourceTypes[0] = sourceType;
					int length = types.length;
					for (int i = 0, index = 1; i < length; i++) {
						ISourceType otherType =
							(ISourceType) ((JavaElement) types[i]).getElementInfo();
						if (!otherType.equals(topLevelType) && index < length) // check that the index is in bounds (see https://bugs.eclipse.org/bugs/show_bug.cgi?id=62861)
							sourceTypes[index++] = otherType;
					}
					return new NameEnvironmentAnswer(sourceTypes, accessRestriction);
				} catch (JavaModelException npe) {
					return null;
				}
			}
		}
		return null;
	}

	/**
	 * @see ISearchableNameEnvironment#findPackages(char[], ISearchRequestor)
	 */
	public void findPackages(char[] prefix, ISearchRequestor requestor) {
		this.nameLookup.seekPackageFragments(
			new String(prefix),
			true,
			new SearchableEnvironmentRequestor(requestor));
	}

	/**
	 * @see org.eclipse.jdt.internal.compiler.env.INameEnvironment#findType(char[][])
	 */
	public NameEnvironmentAnswer findType(char[][] compoundTypeName) {
		if (compoundTypeName == null) return null;

		int length = compoundTypeName.length;
		if (length <= 1) {
			if (length == 0) return null;
			return find(new String(compoundTypeName[0]), null);
		}

		int lengthM1 = length - 1;
		char[][] packageName = new char[lengthM1][];
		System.arraycopy(compoundTypeName, 0, packageName, 0, lengthM1);

		return find(
			new String(compoundTypeName[lengthM1]),
			CharOperation.toString(packageName));
	}

	/**
	 * @see org.eclipse.jdt.internal.compiler.env.INameEnvironment#findType(char[], char[][])
	 */
	public NameEnvironmentAnswer findType(char[] name, char[][] packageName) {
		if (name == null) return null;

		return find(
			new String(name),
			packageName == null || packageName.length == 0 ? null : CharOperation.toString(packageName));
	}

	/**
	 * @see ISearchableNameEnvironment#findTypes(char[], ISearchRequestor)
	 */
	public void findTypes(char[] prefix, final ISearchRequestor storage) {

		/*
			if (true){
				findTypes(new String(prefix), storage, NameLookup.ACCEPT_CLASSES | NameLookup.ACCEPT_INTERFACES);
				return;		
			}
		*/
		try {
			final String excludePath;
			if (this.unitToSkip != null) {
				if (!(this.unitToSkip instanceof IJavaElement)) {
					// revert to model investigation
					findTypes(
						new String(prefix),
						storage,
						NameLookup.ACCEPT_CLASSES | NameLookup.ACCEPT_INTERFACES);
					return;
				}
				excludePath = ((IJavaElement) this.unitToSkip).getPath().toString();
			} else {
				excludePath = null;
			}
			int lastDotIndex = CharOperation.lastIndexOf('.', prefix);
			char[] qualification, simpleName;
			if (lastDotIndex < 0) {
				qualification = null;
				simpleName = CharOperation.toLowerCase(prefix);
			} else {
				qualification = CharOperation.subarray(prefix, 0, lastDotIndex);
				simpleName =
					CharOperation.toLowerCase(
						CharOperation.subarray(prefix, lastDotIndex + 1, prefix.length));
			}

			IProgressMonitor progressMonitor = new IProgressMonitor() {
				boolean isCanceled = false;
				public void beginTask(String name, int totalWork) {
					// implements interface method
				}
				public void done() {
					// implements interface method
				}
				public void internalWorked(double work) {
					// implements interface method
				}
				public boolean isCanceled() {
					return isCanceled;
				}
				public void setCanceled(boolean value) {
					isCanceled = value;
				}
				public void setTaskName(String name) {
					// implements interface method
				}
				public void subTask(String name) {
					// implements interface method
				}
				public void worked(int work) {
					// implements interface method
				}
			};
			ITypeNameRequestor nameRequestor = new ITypeNameRequestor() {
				public void acceptClass(
					char[] packageName,
					char[] simpleTypeName,
					char[][] enclosingTypeNames,
					String path) {
					if (excludePath != null && excludePath.equals(path))
						return;
					if (enclosingTypeNames != null && enclosingTypeNames.length > 0)
						return; // accept only top level types
					storage.acceptClass(packageName, simpleTypeName, IConstants.AccPublic);
				}
				public void acceptInterface(
					char[] packageName,
					char[] simpleTypeName,
					char[][] enclosingTypeNames,
					String path) {
					if (excludePath != null && excludePath.equals(path))
						return;
					if (enclosingTypeNames != null && enclosingTypeNames.length > 0)
						return; // accept only top level types
					storage.acceptInterface(packageName, simpleTypeName, IConstants.AccPublic);
				}
			};
			try {
				new SearchEngine().searchAllTypeNames(
					qualification,
					simpleName,
					SearchPattern.R_PREFIX_MATCH, // not case sensitive
					IJavaSearchConstants.TYPE,
					this.searchScope,
					nameRequestor,
					CANCEL_IF_NOT_READY_TO_SEARCH,
					progressMonitor);
			} catch (OperationCanceledException e) {
				findTypes(
					new String(prefix),
					storage,
					NameLookup.ACCEPT_CLASSES | NameLookup.ACCEPT_INTERFACES);
			}
		} catch (JavaModelException e) {
			findTypes(
				new String(prefix),
				storage,
				NameLookup.ACCEPT_CLASSES | NameLookup.ACCEPT_INTERFACES);
		}
	}

	/**
	 * Returns all types whose name starts with the given (qualified) <code>prefix</code>.
	 *
	 * If the <code>prefix</code> is unqualified, all types whose simple name matches
	 * the <code>prefix</code> are returned.
	 */
	private void findTypes(String prefix, ISearchRequestor storage, int type) {
		SearchableEnvironmentRequestor requestor =
			new SearchableEnvironmentRequestor(storage, this.unitToSkip);
		int index = prefix.lastIndexOf('.');
		if (index == -1) {
			this.nameLookup.seekTypes(prefix, null, true, type, requestor);
		} else {
			String packageName = prefix.substring(0, index);
			JavaElementRequestor elementRequestor = new JavaElementRequestor();
			this.nameLookup.seekPackageFragments(packageName, false, elementRequestor);
			IPackageFragment[] fragments = elementRequestor.getPackageFragments();
			if (fragments != null) {
				String className = prefix.substring(index + 1);
				for (int i = 0, length = fragments.length; i < length; i++)
					if (fragments[i] != null)
						this.nameLookup.seekTypes(className, fragments[i], true, type, requestor);
			}
		}
	}

	/**
	 * @see org.eclipse.jdt.internal.compiler.env.INameEnvironment#isPackage(char[][], char[])
	 */
	public boolean isPackage(char[][] parentPackageName, char[] subPackageName) {
		if (subPackageName == null || CharOperation.contains('.', subPackageName))
			return false;
		if (parentPackageName == null || parentPackageName.length == 0)
			return isTopLevelPackage(subPackageName);
		for (int i = 0, length = parentPackageName.length; i < length; i++)
			if (parentPackageName[i] == null || CharOperation.contains('.', parentPackageName[i]))
				return false;

		String packageName = new String(CharOperation.concatWith(parentPackageName, subPackageName, '.'));
		return this.nameLookup.findPackageFragments(packageName, false) != null;
	}

	public boolean isTopLevelPackage(char[] packageName) {
		return packageName != null &&
			!CharOperation.contains('.', packageName) &&
			this.nameLookup.findPackageFragments(new String(packageName), false) != null;
	}

	/**
	 * Returns a printable string for the array.
	 */
	protected String toStringChar(char[] name) {
		return "["  //$NON-NLS-1$
		+ new String(name) + "]" ; //$NON-NLS-1$
	}

	/**
	 * Returns a printable string for the array.
	 */
	protected String toStringCharChar(char[][] names) {
		StringBuffer result = new StringBuffer();
		for (int i = 0; i < names.length; i++) {
			result.append(toStringChar(names[i]));
		}
		return result.toString();
	}
	
	public void cleanup() {
		// nothing to do
	}
}
