package org.tron.common.runtime.vm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.protobuf.ByteString;
import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.spongycastle.util.encoders.Hex;
import org.tron.common.crypto.Hash;
import org.tron.common.runtime.Runtime;
import org.tron.common.runtime.vm.program.invoke.ProgramInvokeFactoryImpl;
import org.tron.common.storage.DepositImpl;
import org.tron.core.Wallet;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.capsule.TransactionCapsule;
import org.tron.core.db.Manager;
import org.tron.core.db.TransactionTrace;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.exception.ReceiptCheckErrException;
import org.tron.core.exception.VMIllegalException;
import org.tron.protos.Contract.CreateSmartContract;
import org.tron.protos.Contract.TriggerSmartContract;
import org.tron.protos.Protocol.SmartContract;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;

@Slf4j

public class VMTimeBenchmarkUtils {

  public static Transaction generateDeploySmartContractAndGetTransaction(String contractName,
      byte[] callerAddress,
      String ABI, String code, long value, long feeLimit, long consumeUserResourcePercent,
      String libraryAddressPair) {

    CreateSmartContract contract = buildCreateSmartContract(contractName, callerAddress, ABI, code,
        value, consumeUserResourcePercent, libraryAddressPair);
    TransactionCapsule trxCapWithoutFeeLimit = new TransactionCapsule(contract,
        ContractType.CreateSmartContract);
    Transaction.Builder transactionBuilder = trxCapWithoutFeeLimit.getInstance().toBuilder();
    Transaction.raw.Builder rawBuilder = trxCapWithoutFeeLimit.getInstance().getRawData()
        .toBuilder();
    rawBuilder.setFeeLimit(feeLimit);
    transactionBuilder.setRawData(rawBuilder);
    Transaction trx = transactionBuilder.build();
    return trx;
  }


  public static TVMResult deployContractAndReturnTVMResult(String contractName,
      byte[] callerAddress,
      String ABI, String code, long value, long feeLimit, long consumeUserResourcePercent,
      String libraryAddressPair, Manager dbManager, BlockCapsule blockCap)
      throws ContractExeException, ReceiptCheckErrException, ContractValidateException, VMIllegalException {
    Transaction trx = generateDeploySmartContractAndGetTransaction(contractName, callerAddress, ABI,
        code, value, feeLimit, consumeUserResourcePercent, libraryAddressPair);

    byte[] contractAddress = Wallet.generateContractAddress(trx);

    return processTransactionAndReturnTVMResult(trx, dbManager, blockCap)
        .setContractAddress(Wallet.generateContractAddress(trx));
  }


  public static TVMResult triggerContractAndReturnTVMResult(byte[] callerAddress,
      byte[] contractAddress, byte[] data, long callValue, long feeLimit, Manager dbManager,
      BlockCapsule blockCap)
      throws ContractExeException, ReceiptCheckErrException, ContractValidateException, VMIllegalException {
    Transaction trx = generateTriggerSmartContractAndGetTransaction(callerAddress, contractAddress,
        data, callValue, feeLimit);
    return processTransactionAndReturnTVMResult(trx, dbManager, blockCap)
        .setContractAddress(contractAddress);
  }


  private static byte[] replaceLibraryAddress(String code, String libraryAddressPair) {

    String[] libraryAddressList = libraryAddressPair.split("[,]");

    for (int i = 0; i < libraryAddressList.length; i++) {
      String cur = libraryAddressList[i];

      int lastPosition = cur.lastIndexOf(":");
      if (-1 == lastPosition) {
        throw new RuntimeException("libraryAddress delimit by ':'");
      }
      String libraryName = cur.substring(0, lastPosition);
      String addr = cur.substring(lastPosition + 1);
      String libraryAddressHex;
      try {
        libraryAddressHex = (new String(Hex.encode(Wallet.decodeFromBase58Check(addr)),
            "US-ASCII")).substring(2);
      } catch (UnsupportedEncodingException e) {
        throw new RuntimeException(e);  // now ignore
      }
      String repeated = new String(new char[40 - libraryName.length() - 2]).replace("\0", "_");
      String beReplaced = "__" + libraryName + repeated;
      Matcher m = Pattern.compile(beReplaced).matcher(code);
      code = m.replaceAll(libraryAddressHex);
    }
    return Hex.decode(code);
  }


  public static TVMResult processTransactionAndReturnTVMResult(Transaction trx,
      Manager dbManager, BlockCapsule blockCap)
      throws ContractExeException, ContractValidateException, ReceiptCheckErrException, VMIllegalException {
    TransactionCapsule trxCap = new TransactionCapsule(trx);
    TransactionTrace trace = new TransactionTrace(trxCap, dbManager);
    DepositImpl deposit = DepositImpl.createRoot(dbManager);
    Runtime runtime = new Runtime(trace, blockCap, deposit,
        new ProgramInvokeFactoryImpl());

    // init
    trace.init();
    //exec
    long start = System.currentTimeMillis();
    trace.exec(runtime);
    long duration = System.currentTimeMillis() - start;

    trace.finalization(runtime);

    return new TVMResult(runtime, duration, null);
  }


  public static CreateSmartContract buildCreateSmartContract(String contractName,
      byte[] address,
      String ABI, String code, long value, long consumeUserResourcePercent,
      String libraryAddressPair) {
    SmartContract.ABI abi = jsonStr2ABI(ABI);
    if (abi == null) {
      logger.error("abi is null");
      return null;
    }

    SmartContract.Builder builder = SmartContract.newBuilder();
    builder.setName(contractName);
    builder.setOriginAddress(ByteString.copyFrom(address));
    builder.setAbi(abi);
    builder.setConsumeUserResourcePercent(consumeUserResourcePercent);

    if (value != 0) {
      builder.setCallValue(value);
    }
    byte[] byteCode;
    if (null != libraryAddressPair) {
      byteCode = replaceLibraryAddress(code, libraryAddressPair);
    } else {
      byteCode = Hex.decode(code);
    }

    builder.setBytecode(ByteString.copyFrom(byteCode));
    return CreateSmartContract.newBuilder().setOwnerAddress(ByteString.copyFrom(address)).
        setNewContract(builder.build()).build();
  }


  public static Transaction generateTriggerSmartContractAndGetTransaction(
      byte[] callerAddress, byte[] contractAddress, byte[] data, long callValue, long feeLimit) {

    TriggerSmartContract contract = buildTriggerSmartContract(callerAddress, contractAddress, data,
        callValue);
    TransactionCapsule trxCapWithoutFeeLimit = new TransactionCapsule(contract,
        ContractType.TriggerSmartContract);
    Transaction.Builder transactionBuilder = trxCapWithoutFeeLimit.getInstance().toBuilder();
    Transaction.raw.Builder rawBuilder = trxCapWithoutFeeLimit.getInstance().getRawData()
        .toBuilder();
    rawBuilder.setFeeLimit(feeLimit);
    transactionBuilder.setRawData(rawBuilder);
    Transaction trx = transactionBuilder.build();
    return trx;
  }


  public static TriggerSmartContract buildTriggerSmartContract(byte[] address,
      byte[] contractAddress, byte[] data, long callValue) {
    TriggerSmartContract.Builder builder = TriggerSmartContract.newBuilder();
    builder.setOwnerAddress(ByteString.copyFrom(address));
    builder.setContractAddress(ByteString.copyFrom(contractAddress));
    builder.setData(ByteString.copyFrom(data));
    builder.setCallValue(callValue);
    return builder.build();
  }


  private static SmartContract.ABI.Entry.EntryType getEntryType(String type) {
    switch (type) {
      case "constructor":
        return SmartContract.ABI.Entry.EntryType.Constructor;
      case "function":
        return SmartContract.ABI.Entry.EntryType.Function;
      case "event":
        return SmartContract.ABI.Entry.EntryType.Event;
      case "fallback":
        return SmartContract.ABI.Entry.EntryType.Fallback;
      default:
        return SmartContract.ABI.Entry.EntryType.UNRECOGNIZED;
    }
  }


  private static SmartContract.ABI.Entry.StateMutabilityType getStateMutability(
      String stateMutability) {
    switch (stateMutability) {
      case "pure":
        return SmartContract.ABI.Entry.StateMutabilityType.Pure;
      case "view":
        return SmartContract.ABI.Entry.StateMutabilityType.View;
      case "nonpayable":
        return SmartContract.ABI.Entry.StateMutabilityType.Nonpayable;
      case "payable":
        return SmartContract.ABI.Entry.StateMutabilityType.Payable;
      default:
        return SmartContract.ABI.Entry.StateMutabilityType.UNRECOGNIZED;
    }
  }


  private static SmartContract.ABI jsonStr2ABI(String jsonStr) {
    if (jsonStr == null) {
      return null;
    }

    JsonParser jsonParser = new JsonParser();
    JsonElement jsonElementRoot = jsonParser.parse(jsonStr);
    JsonArray jsonRoot = jsonElementRoot.getAsJsonArray();
    SmartContract.ABI.Builder abiBuilder = SmartContract.ABI.newBuilder();
    for (int index = 0; index < jsonRoot.size(); index++) {
      JsonElement abiItem = jsonRoot.get(index);
      boolean anonymous = abiItem.getAsJsonObject().get("anonymous") != null &&
          abiItem.getAsJsonObject().get("anonymous").getAsBoolean();
      boolean constant = abiItem.getAsJsonObject().get("constant") != null &&
          abiItem.getAsJsonObject().get("constant").getAsBoolean();
      String name = abiItem.getAsJsonObject().get("name") != null ?
          abiItem.getAsJsonObject().get("name").getAsString() : null;
      JsonArray inputs = abiItem.getAsJsonObject().get("inputs") != null ?
          abiItem.getAsJsonObject().get("inputs").getAsJsonArray() : null;
      JsonArray outputs = abiItem.getAsJsonObject().get("outputs") != null ?
          abiItem.getAsJsonObject().get("outputs").getAsJsonArray() : null;
      String type = abiItem.getAsJsonObject().get("type") != null ?
          abiItem.getAsJsonObject().get("type").getAsString() : null;
      boolean payable = abiItem.getAsJsonObject().get("payable") != null &&
          abiItem.getAsJsonObject().get("payable").getAsBoolean();
      String stateMutability = abiItem.getAsJsonObject().get("stateMutability") != null ?
          abiItem.getAsJsonObject().get("stateMutability").getAsString() : null;
      if (type == null) {
        logger.error("No type!");
        return null;
      }
      if (!type.equalsIgnoreCase("fallback") && null == inputs) {
        logger.error("No inputs!");
        return null;
      }

      SmartContract.ABI.Entry.Builder entryBuilder = SmartContract.ABI.Entry.newBuilder();
      entryBuilder.setAnonymous(anonymous);
      entryBuilder.setConstant(constant);
      if (name != null) {
        entryBuilder.setName(name);
      }

      /* { inputs : optional } since fallback function not requires inputs*/
      if (null != inputs) {
        for (int j = 0; j < inputs.size(); j++) {
          JsonElement inputItem = inputs.get(j);
          if (inputItem.getAsJsonObject().get("name") == null ||
              inputItem.getAsJsonObject().get("type") == null) {
            logger.error("Input argument invalid due to no name or no type!");
            return null;
          }
          String inputName = inputItem.getAsJsonObject().get("name").getAsString();
          String inputType = inputItem.getAsJsonObject().get("type").getAsString();
          SmartContract.ABI.Entry.Param.Builder paramBuilder = SmartContract.ABI.Entry.Param
              .newBuilder();
          paramBuilder.setIndexed(false);
          paramBuilder.setName(inputName);
          paramBuilder.setType(inputType);
          entryBuilder.addInputs(paramBuilder.build());
        }
      }

      /* { outputs : optional } */
      if (outputs != null) {
        for (int k = 0; k < outputs.size(); k++) {
          JsonElement outputItem = outputs.get(k);
          if (outputItem.getAsJsonObject().get("name") == null ||
              outputItem.getAsJsonObject().get("type") == null) {
            logger.error("Output argument invalid due to no name or no type!");
            return null;
          }
          String outputName = outputItem.getAsJsonObject().get("name").getAsString();
          String outputType = outputItem.getAsJsonObject().get("type").getAsString();
          SmartContract.ABI.Entry.Param.Builder paramBuilder = SmartContract.ABI.Entry.Param
              .newBuilder();
          paramBuilder.setIndexed(false);
          paramBuilder.setName(outputName);
          paramBuilder.setType(outputType);
          entryBuilder.addOutputs(paramBuilder.build());
        }
      }

      entryBuilder.setType(getEntryType(type));
      entryBuilder.setPayable(payable);
      if (stateMutability != null) {
        entryBuilder.setStateMutability(getStateMutability(stateMutability));
      }

      abiBuilder.addEntrys(entryBuilder.build());
    }

    return abiBuilder.build();
  }


  public static byte[] parseABI(String selectorStr, String params) {
    if (params == null) {
      params = "";
    }
    byte[] selector = new byte[4];
    System.arraycopy(Hash.sha3(selectorStr.getBytes()), 0, selector, 0, 4);
    byte[] triggerData = Hex.decode(Hex.toHexString(selector) + params);
    return triggerData;
  }


  public static class TVMResult {

    private Runtime runtime;
    private long duration;
    private byte[] contractAddress;

    public byte[] getContractAddress() {
      return contractAddress.clone();
    }

    public TVMResult setContractAddress(byte[] contractAddress) {
      this.contractAddress = contractAddress.clone();
      return this;
    }

    public Runtime getRuntime() {
      return runtime;
    }

    public TVMResult setRuntime(Runtime runtime) {
      this.runtime = runtime;
      return this;
    }

    public long getDuration() {
      return duration;
    }

    public TVMResult setDuration(long duration) {
      this.duration = duration;
      return this;
    }

    public TVMResult(Runtime runtime, long duration, byte[] contractAddress) {
      this.runtime = runtime;
      this.duration = duration;
      if (null != contractAddress) {
        this.contractAddress = contractAddress.clone();
      } else {
        this.contractAddress = null;
      }
    }

  }

}



