/*
 * load simulation - OpenAPI 3.0
 * describe list of requests
 *
 * The version of the OpenAPI document: 1.0.0
 *
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */


package net.consensys.zkevm.load.swagger;

import java.util.Objects;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.consensys.zkevm.load.model.JSON;

/**
 * The test will create nbWallets new wallets and make each of them send nbTransfers requests to each other in a round robin fashion.
 */

public class RoundRobinMoneyTransfer extends Scenario {
  public static final String SERIALIZED_NAME_NB_TRANSFERS = "nbTransfers";
  @SerializedName(SERIALIZED_NAME_NB_TRANSFERS)
  private Integer nbTransfers = 1;

  public static final String SERIALIZED_NAME_NB_WALLETS = "nbWallets";
  @SerializedName(SERIALIZED_NAME_NB_WALLETS)
  private Integer nbWallets = 1;

  public RoundRobinMoneyTransfer() {
    this.scenarioType = this.getClass().getSimpleName();
  }

  public RoundRobinMoneyTransfer nbTransfers(Integer nbTransfers) {
    this.nbTransfers = nbTransfers;
    return this;
  }

   /**
   * Get nbTransfers
   * @return nbTransfers
  **/
  @javax.annotation.Nullable
  public Integer getNbTransfers() {
    return nbTransfers;
  }

  public void setNbTransfers(Integer nbTransfers) {
    this.nbTransfers = nbTransfers;
  }


  public RoundRobinMoneyTransfer nbWallets(Integer nbWallets) {
    this.nbWallets = nbWallets;
    return this;
  }

   /**
   * Get nbWallets
   * @return nbWallets
  **/
  @javax.annotation.Nullable
  public Integer getNbWallets() {
    return nbWallets;
  }

  public void setNbWallets(Integer nbWallets) {
    this.nbWallets = nbWallets;
  }



  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    RoundRobinMoneyTransfer roundRobinMoneyTransfer = (RoundRobinMoneyTransfer) o;
    return Objects.equals(this.nbTransfers, roundRobinMoneyTransfer.nbTransfers) &&
        Objects.equals(this.nbWallets, roundRobinMoneyTransfer.nbWallets) &&
        super.equals(o);
  }

  @Override
  public int hashCode() {
    return Objects.hash(nbTransfers, nbWallets, super.hashCode());
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class RoundRobinMoneyTransfer {\n");
    sb.append("    ").append(toIndentedString(super.toString())).append("\n");
    sb.append("    nbTransfers: ").append(toIndentedString(nbTransfers)).append("\n");
    sb.append("    nbWallets: ").append(toIndentedString(nbWallets)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }


  public static HashSet<String> openapiFields;
  public static HashSet<String> openapiRequiredFields;

  static {
    // a set of all properties/fields (JSON key names)
    openapiFields = new HashSet<String>();
    openapiFields.add("scenarioType");

    // a set of required properties/fields (JSON key names)
    openapiRequiredFields = new HashSet<String>();
    openapiRequiredFields.add("scenarioType");
  }

 /**
  * Validates the JSON Element and throws an exception if issues found
  *
  * @param jsonElement JSON Element
  * @throws IOException if the JSON Element is invalid with respect to RoundRobinMoneyTransfer
  */
  public static void validateJsonElement(JsonElement jsonElement) throws IOException {
      if (jsonElement == null) {
        if (!RoundRobinMoneyTransfer.openapiRequiredFields.isEmpty()) { // has required fields but JSON element is null
          throw new IllegalArgumentException(String.format("The required field(s) %s in RoundRobinMoneyTransfer is not found in the empty JSON string", RoundRobinMoneyTransfer.openapiRequiredFields.toString()));
        }
      }

      Set<Map.Entry<String, JsonElement>> entries = jsonElement.getAsJsonObject().entrySet();
      // check to see if the JSON string contains additional fields
      for (Map.Entry<String, JsonElement> entry : entries) {
        if (!RoundRobinMoneyTransfer.openapiFields.contains(entry.getKey())) {
          throw new IllegalArgumentException(String.format("The field `%s` in the JSON string is not defined in the `RoundRobinMoneyTransfer` properties. JSON: %s", entry.getKey(), jsonElement.toString()));
        }
      }

      // check to make sure all required properties/fields are present in the JSON string
      for (String requiredField : RoundRobinMoneyTransfer.openapiRequiredFields) {
        if (jsonElement.getAsJsonObject().get(requiredField) == null) {
          throw new IllegalArgumentException(String.format("The required field `%s` is not found in the JSON string: %s", requiredField, jsonElement.toString()));
        }
      }
  }

  public static class CustomTypeAdapterFactory implements TypeAdapterFactory {
    @SuppressWarnings("unchecked")
    @Override
    public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
       if (!RoundRobinMoneyTransfer.class.isAssignableFrom(type.getRawType())) {
         return null; // this class only serializes 'RoundRobinMoneyTransfer' and its subtypes
       }
       final TypeAdapter<JsonElement> elementAdapter = gson.getAdapter(JsonElement.class);
       final TypeAdapter<RoundRobinMoneyTransfer> thisAdapter
                        = gson.getDelegateAdapter(this, TypeToken.get(RoundRobinMoneyTransfer.class));

       return (TypeAdapter<T>) new TypeAdapter<RoundRobinMoneyTransfer>() {
           @Override
           public void write(JsonWriter out, RoundRobinMoneyTransfer value) throws IOException {
             JsonObject obj = thisAdapter.toJsonTree(value).getAsJsonObject();
             elementAdapter.write(out, obj);
           }

           @Override
           public RoundRobinMoneyTransfer read(JsonReader in) throws IOException {
             JsonElement jsonElement = elementAdapter.read(in);
             validateJsonElement(jsonElement);
             return thisAdapter.fromJsonTree(jsonElement);
           }

       }.nullSafe();
    }
  }

 /**
  * Create an instance of RoundRobinMoneyTransfer given an JSON string
  *
  * @param jsonString JSON string
  * @return An instance of RoundRobinMoneyTransfer
  * @throws IOException if the JSON string is invalid with respect to RoundRobinMoneyTransfer
  */
  public static RoundRobinMoneyTransfer fromJson(String jsonString) throws IOException {
    return JSON.getGson().fromJson(jsonString, RoundRobinMoneyTransfer.class);
  }

 /**
  * Convert an instance of RoundRobinMoneyTransfer to an JSON string
  *
  * @return JSON string
  */
  public String toJson() {
    return JSON.getGson().toJson(this);
  }
}

