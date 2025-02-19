// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.api.dto

import com.intellij.collaboration.api.dto.GraphQLFragment

@GraphQLFragment("/graphql/fragment/projectMember.graphql")
data class ProjectMemberDTO(
  val createdBy: GitLabUserDTO,
  val project: GitLabProjectDTO
)