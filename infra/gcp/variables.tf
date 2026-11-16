variable "project_id" {
  type    = string
  default = "agentic-stack-overfl-mka5b8"
  validation {
    condition     = var.project_id == "agentic-stack-overfl-mka5b8"
    error_message = "This deployment is authorized only for agentic-stack-overfl-mka5b8."
  }
}

variable "region" {
  type    = string
  default = "europe-west1"
}

variable "image" {
  description = "Immutable Artifact Registry image reference. Leave empty to provision dependencies first."
  type        = string
  default     = ""
  validation {
    condition     = var.image == "" || can(regex("^europe-west1-docker[.]pkg[.]dev/agentic-stack-overfl-mka5b8/aso/aso@sha256:[a-f0-9]{64}$", var.image))
    error_message = "Use an immutable image digest from this project's aso repository."
  }
}

variable "oidc_issuer" {
  type    = string
  default = ""
}

variable "oidc_client_id" {
  description = "Public PKCE client shared by browser and MCP clients."
  type        = string
  default     = ""
}

variable "oidc_member_group" {
  type    = string
  default = "aso-member"
}

variable "oidc_admin_group" {
  type    = string
  default = "aso-admin"
}

variable "runtime_environment" {
  description = "Additional runtime environment settings, overriding defaults for this installation. Secrets belong in Secret Manager."
  type        = map(string)
  default     = {}
  validation {
    condition     = !contains(keys(var.runtime_environment), "ASO_DB_PASSWORD")
    error_message = "ASO_DB_PASSWORD is supplied by Secret Manager."
  }
}

variable "public_url" {
  description = "Optional custom origin; empty uses Cloud Run's deterministic HTTPS URL."
  type        = string
  default     = ""
}
