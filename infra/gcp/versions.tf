terraform {
  required_version = ">= 1.10, < 2.0"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }

  backend "gcs" {
    bucket = "agentic-stack-overfl-mka5b8-tfstate"
    prefix = "aso"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}
